package momoi.mod.qqpro.api

import com.tencent.qqnt.kernel.api.impl.GroupService
import com.tencent.qqnt.kernel.nativeinterface.*
import com.tencent.qqnt.msg.KernelServiceUtil
import momoi.mod.qqpro.util.ThreadManager
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.runOnUi
import kotlin.concurrent.thread

/**
 * Group announcement (群公告) reader.
 *
 * `getGroupBulletin` returns the full set of *active* announcements via the
 * [IKernelGroupListener.onGroupBulletinChange] callback (the native watch UI also uses this).
 * The richer full-history API `getGroupBulletinList` lives on the raw native service
 * (`IQQNTWrapperSession.getGroupService()`), which the original watch app never calls — this
 * module calls it directly with pagination ([fetchFull]).
 *
 * The body text lives in BulletinFeedsRecord.feedsMsg.feedsContents: each BulletinFeedsContent
 * is a typed chunk carrying its text in contentValue (observed contentType 0 = body text,
 * 10 = title) and image chunks carry a non-empty picUrl. We just collect every non-empty
 * contentValue as text and every non-empty picUrl, which is robust to the exact type numbers.
 * (Text-only announcements come back fully; image announcements may only carry a title + image,
 * mirroring what the native watch app itself shows.)
 */
object GroupBulletinApi {

    /** One image inside an announcement, with everything needed to download it. */
    class Image(val feedsId: String, val fileId: String, val url: String)

    /** One displayable announcement, flattened from a BulletinFeedsRecord. */
    class Item(
        val feedId: String,
        val fromUid: String,
        val time: Int,
        val pinned: Boolean,
        val text: String,
        val images: List<Image>,
    )

    @Volatile
    private var listenerRegistered = false

    // Pending fetch callbacks keyed by group code (onGroupBulletinChange delivers async).
    private val pending = HashMap<Long, MutableList<(List<Item>) -> Unit>>()

    // ---- 完整历史公告（getGroupBulletinList，分页）----
    private class FullFetch(val groupCode: Long, val maxItems: Int) {
        val items = ArrayList<Item>()
        val callbacks = ArrayList<(List<Item>) -> Unit>()
        var nextIndex = 0
        var finished = false
    }

    private val fullPending = HashMap<Long, FullFetch>()

    /**
     * Fetch the active announcements for [groupCode]. [callback] runs on the UI thread with the
     * flattened list (empty list = none / error).
     */
    fun fetch(groupCode: Long, callback: (List<Item>) -> Unit) {
        val svc = KernelServiceUtil.b()
        if (svc == null) {
            Utils.log("GroupBulletin: group service null")
            runOnUi { callback(emptyList()) }
            return
        }
        if (!listenerRegistered) {
            try {
                svc.m(Listener)
                listenerRegistered = true
            } catch (e: Throwable) {
                Utils.log("GroupBulletin: listener register failed: ${e.message}")
            }
        }
        synchronized(pending) {
            pending.getOrPut(groupCode) { mutableListOf() }.add(callback)
        }
        try {
            svc.getGroupBulletin(groupCode) { code, msg ->
                Utils.log("GroupBulletin: getGroupBulletin onResult code=$code msg=$msg gc=$groupCode")
                if (code != 0) deliver(groupCode, emptyList())
            }
        } catch (e: Throwable) {
            Utils.log("GroupBulletin: getGroupBulletin threw: ${e.message}")
            deliver(groupCode, emptyList())
        }
    }

    private fun deliver(groupCode: Long, items: List<Item>) {
        val cbs = synchronized(pending) { pending.remove(groupCode) } ?: return
        runOnUi { cbs.forEach { runCatching { it(items) } } }
    }

    /**
     * Fetch the full announcement history (paged, newest→older) for [groupCode]. Delivers on the
     * UI thread. 走原生 `IKernelGroupService.getGroupBulletinList`：`KernelServiceUtil.g()` 返回
     * `IQQNTWrapperSession`，其 `getGroupService()` 是未被 R8 改名的原生接口，带历史分页能力。
     * 拉取失败/超时按空列表回调（调用方可回退到 [fetch] 的“生效中”公告）。
     */
    fun fetchFull(groupCode: Long, maxItems: Int = 100, callback: (List<Item>) -> Unit) {
        val raw = runCatching { KernelServiceUtil.g()?.getGroupService() }.getOrNull()
        if (raw == null) {
            Utils.log("GroupBulletin: raw group service null")
            runOnUi { callback(emptyList()) }
            return
        }
        if (!listenerRegistered) {
            try {
                KernelServiceUtil.b()?.m(Listener)
                listenerRegistered = true
            } catch (e: Throwable) {
                Utils.log("GroupBulletin: listener register failed: ${e.message}")
            }
        }
        synchronized(fullPending) {
            val f = fullPending.getOrPut(groupCode) {
                FullFetch(groupCode, maxItems).also { requestPage(raw, it) }
            }
            f.callbacks.add(callback)
        }
    }

    private fun requestPage(raw: IKernelGroupService, f: FullFetch) {
        val req = GroupBulletinListReq().apply {
            startIndex = f.nextIndex
            num = minOf(20, f.maxItems - f.items.size).coerceAtLeast(1)
            needInstructionsForJoinGroup = 0
            needPublisherInfo = 0
        }
        Utils.log("GroupBulletin: getGroupBulletinList gc=${f.groupCode} start=${req.startIndex} num=${req.num}")
        runCatching {
            raw.getGroupBulletinList(f.groupCode, "", "", req) { code, msg ->
                Utils.log("GroupBulletin: getGroupBulletinList req code=$code msg=$msg gc=${f.groupCode}")
            }
        }.onFailure {
            Utils.log("GroupBulletin: getGroupBulletinList threw: ${it.message}")
            finishFull(f.groupCode)
        }
        // 超时保护：8 秒没等到 onGetGroupBulletinListResult 就按当前结果交付，避免界面一直转圈。
        ThreadManager.runOnUiThread({
            synchronized(fullPending) {
                val cur = fullPending[f.groupCode]
                if (cur != null && !cur.finished) finishFull(f.groupCode)
            }
        }, 8000L)
    }

    private fun finishFull(groupCode: Long) {
        val f = synchronized(fullPending) {
            fullPending.remove(groupCode)?.also { it.finished = true }
        } ?: return
        runOnUi { f.callbacks.forEach { runCatching { it(f.items) } } }
    }

    private fun feedToItem(feed: GroupBulletinFeed): Item {
        val msg = feed.msg
        val text = listOfNotNull(msg?.title, msg?.text)
            .map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
        // 完整历史的图片只有 picId、无直链 URL，先只展示文字；图片走 activity 接口的直链。
        return Item(
            feedId = feed.feedId,
            fromUid = feed.uin.toString(),
            time = feed.publishTime.toInt(),
            pinned = feed.pinned != 0,
            text = text,
            images = emptyList(),
        )
    }

    private fun flatten(b: GroupBulletin): List<Item> =
        b.feedsRecords.map { rec ->
            val text = StringBuilder()
            val pics = mutableListOf<Image>()
            rec.feedsMsg.feedsContents.forEach { c ->
                // Skip the generic "群公告" title chunk the server appends to almost every
                // announcement: the viewer already shows a "群公告" header, so it would just be a
                // redundant trailing line. Matched by value (not the exact contentType number, which
                // we don't fully trust) — no real announcement body is the lone string "群公告".
                val isGenericTitle = c.contentValue.trim() == "群公告"
                if (c.contentValue.isNotEmpty() && !isGenericTitle) {
                    if (text.isNotEmpty()) text.append('\n')
                    text.append(c.contentValue)
                }
                val url = c.picUrl.ifEmpty { c.fileUrl }
                val id = c.picId.ifEmpty { c.picMd5 }
                if (url.isNotEmpty() || id.isNotEmpty()) {
                    pics.add(Image(rec.feedsId, id, url))
                }
            }
            Item(
                feedId = rec.feedsId,
                fromUid = rec.fromUid,
                time = rec.createTime,
                pinned = rec.setTop != 0,
                text = text.toString(),
                images = pics,
            )
        }

    /**
     * Load one announcement [image] as a Bitmap (null on failure), delivered on the UI thread.
     *
     * The bulletin's picUrl is already a direct image URL (http://gdynamic.qpic.cn/gdynamic/<id>),
     * so we just fetch it over HTTP. The kernel's downloadGroupBulletinRichMedia never fires its
     * completion callback on this watch build, so the URL route is the reliable one.
     */
    fun downloadImage(image: Image, callback: (android.graphics.Bitmap?) -> Unit) {
        val raw = image.url
        if (raw.isEmpty() || !raw.startsWith("http")) {
            Utils.log("GroupBulletin: image has no http url (url='$raw')")
            runOnUi { callback(null) }
            return
        }
        // qpic dynamic URLs (gdynamic.qpic.cn/gdynamic/<id>) 400 without a size segment;
        // append "/0" (original size) when no numeric size suffix is present.
        val url = if ("/gdynamic/" in raw && !Regex("/\\d+$").containsMatchIn(raw)) "$raw/0" else raw
        thread {
            var conn: java.net.HttpURLConnection? = null
            val bmp = try {
                conn = (java.net.URL(url).openConnection() as java.net.HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 15_000
                    instanceFollowRedirects = true
                    // 精华图片等 https 直链在 API 19 上同样需要 TLS1.2 才能握手。
                    if (this is javax.net.ssl.HttpsURLConnection) {
                        TlsUpgrade.enableTls12(this)
                    }
                }
                val code = conn.responseCode
                if (code == java.net.HttpURLConnection.HTTP_OK) {
                    conn.inputStream.use { android.graphics.BitmapFactory.decodeStream(it) }
                } else {
                    Utils.log("GroupBulletin: image http=$code url=${url.take(60)}")
                    null
                }
            } catch (e: Throwable) {
                Utils.log("GroupBulletin: image download failed: ${e.message}")
                null
            } finally {
                conn?.disconnect()
            }
            runOnUi { runCatching { callback(bmp) } }
        }
    }

    private object Listener : IKernelGroupListener {
        override fun onGroupBulletinChange(j: Long, b: GroupBulletin) {
            Utils.log("GroupBulletin: onGroupBulletinChange gc=$j count=${b.feedsRecords.size}")
            deliver(j, flatten(b))
        }

        // --- unused interface methods ---
        override fun onGetGroupBulletinListResult(j: Long, s: String?, r: GroupBulletinListResult) {
            Utils.log("GroupBulletin: onGetGroupBulletinListResult gc=$j err=$s srv=${r.srvCode} feeds=${r.feeds?.size} next=${r.nextIndex}")
            val f = synchronized(fullPending) { fullPending[j] } ?: return
            r.feeds?.forEach { feed ->
                val item = feedToItem(feed)
                if (f.items.none { it.feedId == item.feedId }) f.items.add(item)
            }
            val raw = runCatching { KernelServiceUtil.g()?.getGroupService() }.getOrNull()
            if (raw == null || r.srvCode != 0 || r.feeds.isNullOrEmpty() || r.nextIndex <= 0 || f.items.size >= f.maxItems) {
                finishFull(j)
            } else {
                f.nextIndex = r.nextIndex
                requestPage(raw, f)
            }
        }
        override fun onGroupAdd(j: Long) {}
        override fun onGroupAllInfoChange(p: GroupAllInfo) {}
        override fun onGroupArkInviteStateResult(j: Long, p: GroupArkInviteStateInfo) {}
        override fun onGroupBulletinRemindNotify(j: Long, p: RemindGroupBulletinMsg) {}
        override fun onGroupBulletinRichMediaDownloadComplete(p: BulletinFeedsDownloadInfo) {}
        override fun onGroupBulletinRichMediaProgressUpdate(p: BulletinFeedsDownloadInfo) {}
        override fun onGroupConfMemberChange(j: Long, p: ArrayList<String>) {}
        override fun onGroupDetailInfoChange(p: GroupDetailInfo) {}
        override fun onGroupExtListUpdate(t: GroupExtListUpdateType, p: ArrayList<GroupExtInfo>) {}
        override fun onGroupFirstBulletinNotify(p: FirstGroupBulletinInfo) {}
        override fun onGroupListUpdate(t: GroupListUpdateType, p: ArrayList<GroupSimpleInfo>) {}
        override fun onGroupNotifiesUnreadCountUpdated(z: Boolean, j: Long, i: Int) {}
        override fun onGroupNotifiesUpdated(z: Boolean, p: ArrayList<GroupNotifyMsg>) {}
        override fun onGroupSingleScreenNotifies(z: Boolean, j: Long, p: ArrayList<GroupNotifyMsg>) {}
        override fun onGroupStatisticInfoChange(j: Long, p: GroupStatisticInfo) {}
        override fun onGroupsMsgMaskResult(p: ArrayList<GroupMsgMaskInfo>) {}
        override fun onJoinGroupNoVerifyFlag(j: Long, z: Boolean, z2: Boolean) {}
        override fun onJoinGroupNotify(p: JoinGroupNotifyMsg) {}
        override fun onMemberInfoChange(j: Long, d: DataSource, m: HashMap<String, MemberInfo>) {}
        override fun onMemberListChange(p: GroupMemberListChangeInfo) {}
        override fun onSearchMemberChange(s1: String, s2: String, l: ArrayList<GroupMemberInfoListId>, m: HashMap<String, MemberInfo>) {}
        override fun onShutUpMemberListChanged(j: Long, p: ArrayList<MemberInfo>) {}
    }
}
