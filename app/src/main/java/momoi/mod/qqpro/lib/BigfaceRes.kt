package momoi.mod.qqpro.lib

import android.app.Application
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * First-launch extractor + config builder for the bundled emoji resources that the watch's
 * older QQ build can't download itself (newer 大表情 animated lottie + missing classic faces).
 *
 * The build tool injects `assets/bigface.zip` (tree mirroring the emoticon res dir, layout
 * `qlottie/1/<id>/<id>.json`, `sysface_res/static|apng/s<id>.png`) and `assets/bigface_index.json`
 * (`{"ids":[...]}`) into the APK. On first launch we unzip into the app's own
 * `filesDir/qq_emoticon_res/` (no root needed — the app owns that dir) so the unmodified renderer
 * (`WatchAniStickerItemCell` → `AniStickerHelper`) finds the lottie at the path it computes.
 *
 * Config injection (in [appendMissingSysface]) adds the matching sysface entries so received
 * faces resolve. See EMOJI_BIGFACE_PLAN.md and memories emoji-sysface-config / emoji-animated-render-path.
 *
 * Logic lives here (a non-inline lib helper), NOT inside the @Mixin method body, per the
 * anon-class rule (qqpro-mixin-anon-class).
 */
object BigfaceRes {

    /** Bump when the bundled zip content changes so the extractor re-runs after an app update. */
    const val VERSION = 1

    private const val ZIP_ASSET = "bigface.zip"
    private const val INDEX_ASSET = "bigface_index.json"
    private const val RES_DIRNAME = "qq_emoticon_res"

    /**
     * Extract the bundled zip into `filesDir/qq_emoticon_res/` if the version marker is absent or
     * stale. Idempotent and cheap on subsequent launches (just a marker file check).
     */
    fun ensureExtracted(app: Application) {
        try {
            val resDir = File(app.filesDir, RES_DIRNAME)
            val marker = File(resDir, ".qqpro_bigface_v$VERSION")
            if (marker.isFile) return
            resDir.mkdirs()
            var count = 0
            app.assets.open(ZIP_ASSET).use { raw ->
                ZipInputStream(raw.buffered()).use { zis ->
                    while (true) {
                        val entry = zis.nextEntry ?: break
                        if (entry.isDirectory) { zis.closeEntry(); continue }
                        val out = File(resDir, entry.name)
                        // Guard against zip-slip; entries are all relative and trusted but cheap to check.
                        if (!out.canonicalPath.startsWith(resDir.canonicalPath + File.separator)) {
                            zis.closeEntry(); continue
                        }
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zis.copyTo(it) }
                        zis.closeEntry()
                        count++
                    }
                }
            }
            marker.writeText(System.currentTimeMillis().toString())
            log("BigfaceRes: extracted $count file(s) into ${resDir.absolutePath}")
        } catch (e: Throwable) {
            log("BigfaceRes.ensureExtracted error: ${android.util.Log.getStackTraceString(e)}")
        }
    }

    /**
     * Append the missing classic-static entries and the bundled animated (大表情) entries into the
     * [sysface] array (in place), deduping by QSid against entries already present. Returns the
     * number of entries actually added.
     */
    fun appendMissingSysface(app: Application, sysface: JSONArray): Int {
        val existing = HashSet<String>()
        for (i in 0 until sysface.length()) {
            sysface.optJSONObject(i)?.optString("QSid")?.takeIf { it.isNotEmpty() }?.let { existing.add(it) }
        }
        var added = 0

        // Classic static faces (/足球 /礼物 …) whose f_static_<AQLid> drawables ship in the APK but
        // whose config mapping the watch lacks. Derived offline by diffing phone vs watch face_config.
        val classic = JSONArray(CLASSIC_STATIC_ENTRIES)
        for (i in 0 until classic.length()) {
            val o = classic.getJSONObject(i)
            if (existing.add(o.getString("QSid"))) { sysface.put(o); added++ }
        }

        // Animated 大表情: one entry per bundled lottie id. AniStickerId == id so the renderer's
        // path getAniStickerResPath(packId, id) = qlottie/1/<id>/<id>.json resolves to our file.
        for (id in animatedIds(app)) {
            if (existing.add(id)) {
                sysface.put(JSONObject().apply {
                    put("QSid", id)
                    put("AQLid", id)
                    put("IQLid", id)
                    put("AniStickerType", 1)
                    put("AniStickerPackId", "1")
                    put("AniStickerId", id)
                    put("QDes", "/超表情$id")
                    put("EMCode", "10$id")
                })
                added++
            }
        }
        return added
    }

    private fun animatedIds(app: Application): List<String> {
        return try {
            val text = app.assets.open(INDEX_ASSET).use { it.readBytes().toString(Charsets.UTF_8) }
            val arr = JSONObject(text).optJSONArray("ids") ?: return emptyList()
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Throwable) {
            log("BigfaceRes.animatedIds error: ${android.util.Log.getStackTraceString(e)}")
            emptyList()
        }
    }

    private fun log(msg: String) {
        try {
            momoi.mod.qqpro.util.Utils.log(msg)
        } catch (e: Throwable) {
        }
    }

    /**
     * The 39 classic-static sysface entries the watch's bundled config omits but whose
     * f_static_<AQLid> drawables exist in-APK. Generated from a phone↔watch face_config.json diff.
     */
    private const val CLASSIC_STATIC_ENTRIES = """[
{"QSid":"245","QDes":"/加油必胜","IQLid":"245","AQLid":"217","EMCode":"202001"},
{"QSid":"246","QDes":"/加油抱抱","IQLid":"246","AQLid":"218","EMCode":"202002"},
{"QSid":"247","QDes":"/口罩护体","IQLid":"247","AQLid":"219","EMCode":"202003"},
{"QSid":"113","QDes":"/啤酒","IQLid":"61","AQLid":"61","EMCode":"157"},
{"QSid":"115","QDes":"/乒乓","IQLid":"91","AQLid":"93","EMCode":"159"},
{"QSid":"61","QDes":"/饭","isStatic":"1","IQLid":"58","AQLid":"58","EMCode":"161"},
{"QSid":"54","QDes":"/闪电","isStatic":"1","IQLid":"78","AQLid":"80","EMCode":"169"},
{"QSid":"145","QDes":"/祈祷","isStatic":"1","IQLid":"115","AQLid":"117","EMCode":"121010"},
{"QSid":"57","QDes":"/足球","IQLid":"75","AQLid":"77","EMCode":"172"},
{"QSid":"117","QDes":"/瓢虫","IQLid":"62","AQLid":"62","EMCode":"173"},
{"QSid":"69","QDes":"/礼物","isStatic":"1","IQLid":"74","AQLid":"76","EMCode":"177"},
{"QSid":"126","QDes":"/磕头","IQLid":"96","AQLid":"98","EMCode":"196"},
{"QSid":"127","QDes":"/回头","IQLid":"97","AQLid":"99","EMCode":"197"},
{"QSid":"128","QDes":"/跳绳","IQLid":"98","AQLid":"100","EMCode":"198"},
{"QSid":"130","QDes":"/激动","IQLid":"99","AQLid":"101","EMCode":"200"},
{"QSid":"131","QDes":"/街舞","IQLid":"100","AQLid":"102","EMCode":"201"},
{"QSid":"132","QDes":"/献吻","IQLid":"101","AQLid":"103","EMCode":"202"},
{"QSid":"133","QDes":"/左太极","IQLid":"102","AQLid":"104","EMCode":"203"},
{"QSid":"134","QDes":"/右太极","IQLid":"103","AQLid":"105","EMCode":"204"},
{"QSid":"136","QDes":"/双喜","isStatic":"1","IQLid":"106","AQLid":"108","EMCode":"121001"},
{"QSid":"138","QDes":"/灯笼","isStatic":"1","IQLid":"108","AQLid":"110","EMCode":"121003"},
{"QSid":"140","QDes":"/K歌","isStatic":"1","IQLid":"110","AQLid":"112","EMCode":"121005"},
{"QSid":"151","QDes":"/飞机","isStatic":"1","IQLid":"121","AQLid":"123","EMCode":"121016"},
{"QSid":"158","QDes":"/钞票","isStatic":"1","IQLid":"128","AQLid":"130","EMCode":"121023"},
{"QSid":"168","QDes":"/药","isStatic":"1","IQLid":"138","AQLid":"140","EMCode":"121033"},
{"QSid":"188","QDes":"/蛋","IQLid":"158","AQLid":"180","EMCode":"258"},
{"QSid":"192","QDes":"/红包","IQLid":"162","AQLid":"184","EMCode":"262"},
{"QSid":"184","QDes":"/河蟹","IQLid":"154","AQLid":"176","EMCode":"254"},
{"QSid":"190","QDes":"/菊花","IQLid":"160","AQLid":"182","EMCode":"260"},
{"QSid":"197","QDes":"/冷漠","IQLid":"167","AQLid":"146","EMCode":"267"},
{"QSid":"199","QDes":"/好棒","IQLid":"169","AQLid":"148","EMCode":"269"},
{"QSid":"205","QDes":"/送花","IQLid":"175","AQLid":"154","EMCode":"275"},
{"QSid":"207","QDes":"/花痴","IQLid":"177","AQLid":"156","EMCode":"277"},
{"QSid":"208","QDes":"/小样儿","IQLid":"178","AQLid":"157","EMCode":"278"},
{"QSid":"242","QDes":"/头撞击","IQLid":"212","isCMEmoji":"1","AQLid":"214","EMCode":"314"},
{"QSid":"220","QDes":"/拽炸天","IQLid":"190","isCMEmoji":"1","AQLid":"192","EMCode":"290"},
{"QSid":"236","QDes":"/啃头","IQLid":"206","isCMEmoji":"1","AQLid":"208","EMCode":"306"},
{"QSid":"228","QDes":"/恭喜","IQLid":"198","isCMEmoji":"1","AQLid":"200","EMCode":"298"},
{"QSid":"234","QDes":"/惊呆","IQLid":"204","isCMEmoji":"1","AQLid":"206","EMCode":"304"}
]"""
}
