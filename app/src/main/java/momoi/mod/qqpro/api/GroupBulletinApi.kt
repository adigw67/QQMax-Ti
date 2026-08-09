package momoi.mod.qqpro.api

import com.tencent.qqnt.kernel.api.impl.GroupService
import com.tencent.qqnt.kernel.nativeinterface.*
import com.tencent.qqnt.msg.KernelServiceUtil
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.runOnUi
import kotlin.concurrent.thread

/**
 * Group announcement (群公告) reader.
 *
 * Only `getGroupBulletin` works on this watch build: it returns the full set of *active*
 * announcements via the [IKernelGroupListener.onGroupBulletinChange] callback. The richer
 * `getGroupBulletinList` (full history) returns "server get bulletin list err" and the
 * web `list_announce` cgi is dead code here — so active announcements are all we can fetch.
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
        override fun onGetGroupBulletinListResult(j: Long, s: String?, r: GroupBulletinListResult) {}
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
