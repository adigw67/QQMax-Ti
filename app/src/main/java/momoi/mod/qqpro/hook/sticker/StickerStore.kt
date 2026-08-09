package momoi.mod.qqpro.hook.sticker

import com.tencent.mobileqq.emoticonview.EmoticonInfo
import com.tencent.mobileqq.emoticonview.IPicEmoticonInfo
import com.tencent.mobileqq.qroute.QRoute
import com.tencent.qqnt.emotion.adapter.api.IMarketFaceApi
import com.tencent.qqnt.emotion.api.IEmoticonManagerService
import com.tencent.qqnt.kernel.nativeinterface.Contact
import com.tencent.qqnt.kernel.nativeinterface.CustomEmotionData
import com.tencent.qqnt.kernel.nativeinterface.IFetchMarketEmoticonListCallback
import com.tencent.qqnt.kernel.nativeinterface.IGProFetchFavEmojiListCallback
import com.tencent.qqnt.kernel.nativeinterface.IOperateCallback
import com.tencent.qqnt.kernel.nativeinterface.MarketEmoticonInfo
import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.qqnt.msg.KernelServiceUtil
import com.tencent.qqnt.watch.emotion.util.EmosmUtils
import momoi.mod.qqpro.MsgUtil
import momoi.mod.qqpro.enums.ElementType
import momoi.mod.qqpro.hook.action.CurrentContact
import momoi.mod.qqpro.util.ThreadManager
import momoi.mod.qqpro.util.Utils
import org.json.JSONObject
import java.io.File
import java.util.ArrayList

/**
 * Data + send layer for the store-sticker (商城表情) picker. Uses the kernel emoticon APIs proven to
 * work on the watch:
 *  - [loadPacks]    fetchMarketEmoticonList → the user's owned packs (added on the phone).
 *  - [loadStickers] fetchMarketEmotionJsonFile → the pack's JSON, parsed into its `imgs[]` stickers.
 *  - [send]         EmosmUtils.a (build MarkFaceMessage) + EmosmUtils.b (encrypt keys) +
 *                   IMarketFaceApi.createMarketFaceElement → a `MsgElement(type=11)` → sendMsg.
 *                   This mirrors the phone/native `WatchAIOListVM.convertJsonFileToMarkFaceMessage`.
 *
 * Stickers live on disk at `.emotionsm/<epId>/<eId>` (an animated GIF whose 13-byte header is
 * scrambled — see [[marketface-gif-scramble]]) with a `<eId>_aio.png` static sibling.
 */
object StickerStore {

    data class Pack(val epId: Int, val name: String)
    data class Sticker(val epId: Int, val eId: String, val name: String, val w: Int, val h: Int)

    // epId -> the pack's detail-JSON path (from fetchMarketEmotionJsonFile), needed to build a
    // MarkFaceMessage for both sending and thumbnail rendering. Cached on loadStickers.
    private val jsonPaths = java.util.concurrent.ConcurrentHashMap<Int, String>()

    /** The emotion-store roots QQ syncs to disk. */
    private val emotionsmDir: File?
        get() = runCatching {
            File(Utils.application.getExternalFilesDir(null)?.parentFile, "Tencent/MobileQQ/.emotionsm")
        }.getOrNull()

    // ---- packs ---------------------------------------------------------------------------------

    /** Owned market-emoticon packs (synced from the phone). Callback runs on the UI thread. */
    fun loadPacks(onResult: (List<Pack>) -> Unit) {
        Thread {
            runCatching {
                val ks = KernelServiceUtil.g()?.msgService
                if (ks == null) { post(onResult, emptyList()); return@runCatching }
                ks.fetchMarketEmoticonList(0, 0, object : IFetchMarketEmoticonListCallback {
                    override fun onFetchMarketEmoticonListCallback(code: Int, msg: String?, info: MarketEmoticonInfo?) {
                        val tab = info?.roamEmojiTab
                        val packs = ArrayList<Pack>()
                        tab?.ordinaryTabinfoList?.forEach { packs.add(Pack(it.epId, it.tabName ?: it.epId.toString())) }
                        tab?.smallTabinfoList?.forEach { packs.add(Pack(it.epId, it.tabName ?: it.epId.toString())) }
                        Utils.log("StickerStore.loadPacks: code=$code packs=${packs.size}")
                        post(onResult, packs)
                    }
                })
            }.onFailure { Utils.log("StickerStore.loadPacks err: $it"); post(onResult, emptyList()) }
        }.start()
    }

    // ---- stickers of a pack --------------------------------------------------------------------

    /** Stickers of [epId]: fetch the pack JSON file, parse its `imgs[]`. Callback on UI thread. */
    fun loadStickers(epId: Int, onResult: (List<Sticker>) -> Unit) {
        Thread {
            val ks = KernelServiceUtil.g()?.msgService
            if (ks == null) { post(onResult, emptyList()); return@Thread }
            // fetchMarketEmotionJsonFile downloads (if needed) and returns the JSON path in the callback.
            ks.fetchMarketEmotionJsonFile(epId, object : IOperateCallback {
                override fun onResult(code: Int, jsonPath: String?) {
                    if (!jsonPath.isNullOrBlank()) jsonPaths[epId] = jsonPath
                    val list = runCatching { parsePackJson(epId, jsonPath) }
                        .onFailure { Utils.log("StickerStore.parse err: $it") }
                        .getOrDefault(emptyList())
                    Utils.log("StickerStore.loadStickers: epId=$epId code=$code path=$jsonPath count=${list.size}")
                    post(onResult, list)
                }
            })
        }.start()
    }

    /**
     * Aggregate the stickers of EVERY owned pack, for the picker's global "搜索全部" search. Each pack
     * is fetched via [loadStickers] (they run in parallel); [onResult] fires once on the UI thread when
     * all packs have resolved, with the flattened list (pack order preserved as best-effort).
     */
    fun loadAllStickers(packs: List<Pack>, onResult: (List<Sticker>) -> Unit) {
        if (packs.isEmpty()) { post(onResult, emptyList()); return }
        val byPack = arrayOfNulls<List<Sticker>>(packs.size)
        val remaining = java.util.concurrent.atomic.AtomicInteger(packs.size)
        packs.forEachIndexed { i, pack ->
            loadStickers(pack.epId) { list ->
                byPack[i] = list
                if (remaining.decrementAndGet() == 0) {
                    val all = ArrayList<Sticker>()
                    byPack.forEach { it?.let(all::addAll) }
                    Utils.log("StickerStore.loadAllStickers: packs=${packs.size} total=${all.size}")
                    onResult(all)
                }
            }
        }
    }

    /** Parse the pack detail JSON (top-level `imgs[]`, each `{id,name,wWidthInPhone,wHeightInPhone}`). */
    private fun parsePackJson(epId: Int, jsonPath: String?): List<Sticker> {
        val text = jsonPath?.let { p ->
            val f = File(p)
            if (f.exists()) f.readText() else p // the kernel sometimes hands back the content directly
        } ?: return emptyList()
        val obj = JSONObject(text)
        val imgs = obj.optJSONArray("imgs") ?: return emptyList()
        val out = ArrayList<Sticker>(imgs.length())
        for (i in 0 until imgs.length()) {
            val o = imgs.optJSONObject(i) ?: continue
            val eId = o.optString("id").takeIf { it.isNotEmpty() } ?: continue
            out.add(Sticker(epId, eId, o.optString("name"), o.optInt("wWidthInPhone", 200), o.optInt("wHeightInPhone", 200)))
        }
        return out
    }

    // ---- on-disk files (thumbnail / animated) --------------------------------------------------

    /** A representative thumbnail for a pack (its first sticker), for the chip leading icon. */
    fun packIcon(epId: Int, onFile: (File?) -> Unit) {
        loadStickers(epId) { list ->
            val first = list.firstOrNull()
            if (first == null) onFile(null) else thumbFile(first, onFile)
        }
    }

    /** The animated source file `.emotionsm/<epId>/<eId>` if already downloaded, else null. */
    fun rawFile(s: Sticker): File? = emotionsmDir?.let { File(it, "${s.epId}/${s.eId}") }?.takeIf { it.exists() && it.length() > 0 }

    /** The static AIO png `.emotionsm/<epId>/<eId>_aio.png` if present, else null. */
    fun aioPng(s: Sticker): File? = emotionsmDir?.let { File(it, "${s.epId}/${s.eId}_aio.png") }?.takeIf { it.exists() && it.length() > 0 }

    /**
     * Resolve a renderable thumbnail FILE for [s]. The static `_aio.png` is written to disk by QQ's
     * emoticon system once a sticker has been rendered; render that directly (a plain PNG — reliable,
     * unlike the URLDrawable which didn't paint in a bare ImageView). For a not-yet-cached sticker we
     * run the cell's own downloading path ([IMarketFaceApi.fetchMarketFaceInfoSuspend], which writes
     * the png) then poll for it. [onFile] runs on the UI thread (null = unavailable).
     */
    fun thumbFile(s: Sticker, onFile: (File?) -> Unit) {
        aioPng(s)?.let { onFile(it); return }
        val jsonPath = jsonPaths[s.epId] ?: run { onFile(null); return }
        val api = KernelServiceUtil.c() ?: run { onFile(null); return }
        Thread {
            runCatching {
                val mfm = EmosmUtils.a.a(s.epId, s.eId, jsonPath) ?: run { post(onFile, null); return@Thread }
                // Fetch the encrypt key (same as send) so the emoticon's image URL is valid — without it
                // the URLDrawable's URL is wrong and downloadImediatly() never completes (status 0).
                EmosmUtils.a.b(api, s.epId, s.eId, { keys ->
                    Thread {
                        runCatching {
                            keys?.values?.firstOrNull()?.let { mfm.h = it.toByteArray(Charsets.UTF_8) }
                            if (mfm.h == null) mfm.h = ByteArray(0)
                            if (mfm.l == null) mfm.l = ByteArray(0)
                            if (mfm.m == null) mfm.m = ByteArray(0)
                            val app = mqq.app.MobileQQ.getMobileQQ().peekAppRuntime()
                            val ems = app?.getRuntimeService(IEmoticonManagerService::class.java, "")
                            val info = ems?.syncGetEmoticonInfo<EmoticonInfo>(mfm) as? IPicEmoticonInfo
                            val d = info?.i("fromAIO", true)
                            runCatching { d?.downloadImediatly() }
                            var f = aioPng(s); var t = 0
                            while (f == null && t < 30) { Thread.sleep(150); f = aioPng(s); t++ }
                            Utils.log("StickerStore.thumbFile epId=${s.epId} eId=${s.eId} keys=${keys?.size} status=${runCatching { d?.status }.getOrNull()} file=${f?.name}")
                            post(onFile, f)
                        }.onFailure { Utils.log("thumbFile render err: $it"); post(onFile, aioPng(s)) }
                    }.start()
                    kotlin.Unit
                }, {
                    Utils.log("thumbFile: encrypt key fetch failed epId=${s.epId} eId=${s.eId}")
                    post(onFile, null)
                    kotlin.Unit
                })
            }.onFailure { Utils.log("StickerStore.thumbFile err: $it"); post(onFile, aioPng(s)) }
        }.start()
    }

    // ---- send ----------------------------------------------------------------------------------

    /**
     * Send sticker [s] to the current chat. Mirrors the native watch path:
     * fetch the pack JSON → EmosmUtils.a builds a MarkFaceMessage → EmosmUtils.b fetches the encrypt
     * key (set into MarkFaceMessage.h) → IMarketFaceApi.createMarketFaceElement → MsgElement(type=11)
     * → sendMsg. [onDone] reports success/failure on the UI thread.
     */
    fun send(s: Sticker, onDone: (Boolean) -> Unit = {}) {
        Thread {
            runCatching {
                val ks = KernelServiceUtil.g()?.msgService ?: run { post(onDone, false); return@runCatching }
                val api = KernelServiceUtil.c() ?: run { post(onDone, false); return@runCatching }
                ks.fetchMarketEmotionJsonFile(s.epId, object : IOperateCallback {
                    override fun onResult(code: Int, jsonPath: String?) {
                        if (code != 0 || jsonPath.isNullOrBlank()) { Utils.log("send: json fetch failed code=$code"); post(onDone, false); return }
                        Thread {
                            runCatching {
                                val mfm = EmosmUtils.a.a(s.epId, s.eId, jsonPath)
                                if (mfm == null) { Utils.log("send: EmosmUtils.a null"); post(onDone, false); return@Thread }
                                // Fetch the encrypt key, set it into the message, build + send.
                                EmosmUtils.a.b(api, s.epId, s.eId, { keys ->
                                    runCatching {
                                        val key = keys?.values?.firstOrNull()
                                        if (key != null) mfm.h = key.toByteArray(Charsets.UTF_8)
                                        val element = QRoute.api(IMarketFaceApi::class.java).createMarketFaceElement(mfm)
                                        val el = MsgElement().apply { elementType = ElementType.MFACE; marketFaceElement = element }
                                        val contact = Contact(CurrentContact.chatType, CurrentContact.peerUid, "")
                                        MsgUtil.msgService.sendMsg(contact, 0L, arrayListOf(el), IOperateCallback { c, m ->
                                            Utils.log("StickerStore.send result: epId=${s.epId} eId=${s.eId} code=$c msg=$m")
                                            post(onDone, c == 0)
                                        })
                                    }.onFailure { Utils.log("send build/send err: $it"); post(onDone, false) }
                                    kotlin.Unit
                                }, {
                                    Utils.log("send: encrypt keys fetch failed")
                                    post(onDone, false)
                                    kotlin.Unit
                                })
                            }.onFailure { Utils.log("send inner err: $it"); post(onDone, false) }
                        }.start()
                    }
                })
            }.onFailure { Utils.log("StickerStore.send err: $it"); post(onDone, false) }
        }.start()
    }

    private fun <T> post(cb: (T) -> Unit, value: T) = ThreadManager.runOnUiThread({ cb(value) })

    // ---- fav (saved) stickers — bonus source ---------------------------------------------------

    /** The user's saved/favourite stickers (incl. market faces). Callback on UI thread. */
    fun loadFav(onResult: (List<CustomEmotionData>) -> Unit) {
        Thread {
            runCatching {
                KernelServiceUtil.c()?.fetchFavEmojiList("", 50, false, true, object : IGProFetchFavEmojiListCallback {
                    override fun onFetchFavEmojiListCallback(code: Int, msg: String?, list: ArrayList<CustomEmotionData>?) {
                        post(onResult, list?.toList() ?: emptyList())
                    }
                }) ?: post(onResult, emptyList())
            }.onFailure { Utils.log("StickerStore.loadFav err: $it"); post(onResult, emptyList()) }
        }.start()
    }
}
