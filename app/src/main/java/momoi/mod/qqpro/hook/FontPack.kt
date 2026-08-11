package momoi.mod.qqpro.hook

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.os.SystemClock
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.util.Base64
import android.util.SparseArray
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.safeCacheDir
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.runOnUi
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.Inflater
import kotlin.concurrent.thread

/**
 * 可选联网字体包：MiSans（优先显示）+ GNU Unifont（缺失字形兜底）。
 *
 * 仅本应用进程内生效（不动 /system）：
 *  - [applyDefaults] 用反射把进程内默认 Typeface 换成 MiSans —— 之后创建的 TextView 全部
 *    优先用 MiSans 渲染；
 *  - [fallback] 对 mod 自己渲染的文本做逐字符兜底：MiSans 覆盖不到的码位（生僻字/扩展区）
 *    用 Unifont（Unifont 覆盖整个 Unicode BMP + 扩展区）。
 *
 * 下载源（官方服务器）：
 *  - MiSans：小米官方 https://hyperos.mi.com/font-download/MiSans.zip（227MB 全家桶；
 *    这里用 HTTP Range 只拉 zip 里的 MiSans-Regular.ttf / MiSans-Medium.ttf /
 *    MiSans-Bold.ttf 三个条目，实际约 16MB，不解压整包）
 *  - Unifont：GNU 官方镜像（unifoundry.com / ftp.gnu.org 的 TLS 配置在手表上握手被拒，
 *    清华镜像在手表上同样握手异常；阿里镜像 TLS 可用但要求浏览器 UA，否则 403。
 *    所以主源用阿里（带 UA）+ 清华/官方源兜底，文件与 ftp.gnu.org 字节一致）
 */
object FontPack {
    private const val MI_ZIP_URL = "https://hyperos.mi.com/font-download/MiSans.zip"
    private const val MI_REGULAR = "MiSans/ttf/MiSans-Regular.ttf"
    private const val MI_MEDIUM = "MiSans/ttf/MiSans-Medium.ttf"
    private const val MI_BOLD = "MiSans/ttf/MiSans-Bold.ttf"
    private const val UNI_URL =
        "https://mirrors.aliyun.com/gnu/unifont/unifont-17.0.05/unifont-17.0.05.otf"
    private val UNI_FALLBACKS = arrayOf(
        "https://mirrors.tuna.tsinghua.edu.cn/gnu/unifont/unifont-17.0.05/unifont-17.0.05.otf",
        "https://ftp.gnu.org/gnu/unifont/unifont-17.0.05/unifont-17.0.05.otf",
    )
    private const val BROWSER_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/120.0 Safari/537.36"

    @Volatile
    private var busy = false

    private var miTypeface: Typeface? = null
    private var mediumTypeface: Typeface? = null
    private var boldTypeface: Typeface? = null
    private var uniTypeface: Typeface? = null
    // MiSans 的 BMP 覆盖位图：构建时从官方 MiSans-Regular.ttf 的 cmap 精确生成（8192 字节，
    // Base64 内嵌）。运行时 O(1) 查表，不读字体文件、没有解析失败模式。
    private const val MI_BMP_B64 = "AiQAAP///////////////wAAAAD/////////////////////////////////////AoUEAyOgmQAP4P8f8MMB/gAAAM8AAIAAAAD////9f28f/zf7/z+NGcIfA3/vMwgA/58E9/kXFT7AfwAQBgAwQPDX///7/////39eAAAAAAD///////////////8MDDwAAACPPAzAzw8ABAADDMMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAACAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAiKPFCQDwfwAAAAAQAQAACPADAAAAAAAAAwAAAAAAAMAAAAAAAAAAAD8AAED//////////////wMAAAAAAAAAACAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAHAGeXdnA//enEMiAN8D84//YwAAGFsCpwAAAAAAAAAAKALYQEdAAAAAABh4////AwcAzwMAAAAAEAAAAAAAAABEgSbkqU/wMAARBADzwAAAAAAgAiAAAIAAAAAAAAAAAAAABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/wPw/////w8AAAAAAAAAAAAAAAD///////////8P/////w8A/v84AAMADDDAzAAAPAAAAGACAAAAAAAABQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAoAAAAAAAAAAB4AAAAAP//DwAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAEhmAAIBEyAgABAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAP8P7///YP4DAED+////////////H37+/////////////3fg/////wMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAD/AwIAAAAAAAAAAAAAAAAACAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAADAAHACAAAAEEAmAAAAAAD///////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////8/AAAAAAAAAAAA////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////////AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAABgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAgAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAQAAAAAAAAAAAAAgAAIAAAAAAAAAAAAIAAAgAA8BqBmwMAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA/wMAAPv/H/73/n8PAAAAAAAAAAAAAAAAAAAAAAAA/v////////////9/AAAAAAAAAAAAAAAAAAAAAD8AAAA="
    private val miBmp: ByteArray by lazy { Base64.decode(MI_BMP_B64, Base64.DEFAULT) }
    // MiSans 在 BMP 之外的覆盖区间（构建期从同一 cmap 生成，升序闭区间，每对 [start,end]）。
    // 生僻扩展区字（如 U+20087 𠂇）也用 MiSans，不必退回 Unifont。180 组 / 360 int ≈ 1.4KB。
    private val miExtRanges: IntArray = intArrayOf(
        131207, 131207, 131209, 131209, 131276, 131276, 131428, 131428, 132726, 132726, 134352, 134352,
        136090, 136090, 136211, 136211, 136663, 136663, 141711, 141711, 144843, 144843, 146583, 146584,
        146979, 146979, 147966, 147966, 149979, 149979, 150141, 150141, 150217, 150217, 152882, 152882,
        152930, 152930, 153000, 153000, 155351, 155351, 156193, 156193, 156813, 156813, 157302, 157302,
        157564, 157564, 158556, 158556, 158753, 158753, 163833, 163833, 164872, 164872, 165496, 165496,
        165525, 165525, 165856, 165856, 166729, 166729, 166983, 166983, 166991, 166991, 166993, 166993,
        166996, 166996, 167577, 167577, 171902, 171902, 171907, 171907, 171916, 171916, 174045, 174045,
        174331, 174331, 174359, 174359, 174640, 174640, 174646, 174646, 174680, 174680, 176034, 176034,
        176423, 176424, 176439, 176440, 176621, 176621, 176896, 176896, 176995, 176995, 177007, 177007,
        177010, 177010, 177021, 177021, 177156, 177156, 177168, 177168, 177171, 177171, 177249, 177249,
        177383, 177383, 177391, 177391, 177398, 177398, 177401, 177401, 177421, 177422, 177462, 177462,
        177582, 177583, 177587, 177587, 177639, 177639, 177652, 177652, 177692, 177693, 177702, 177704,
        177706, 177706, 177708, 177708, 177813, 177814, 177837, 177837, 177901, 177901, 178089, 178089,
        178117, 178117, 178150, 178150, 178169, 178169, 178172, 178172, 178182, 178182, 178186, 178186,
        178204, 178204, 178360, 178360, 178887, 178887, 179039, 179039, 179042, 179042, 179068, 179068,
        179075, 179075, 179227, 179227, 179575, 179575, 179591, 179591, 179703, 179703, 179753, 179753,
        180265, 180266, 180393, 180393, 180426, 180426, 180693, 180693, 180697, 180697, 180729, 180729,
        180860, 180860, 180872, 180872, 180900, 180900, 181015, 181015, 181083, 181083, 181089, 181089,
        181092, 181092, 181384, 181384, 181396, 181396, 181399, 181399, 181570, 181570, 181779, 181779,
        181784, 181784, 181793, 181793, 181801, 181801, 181803, 181805, 181807, 181807, 181826, 181826,
        181834, 181835, 182060, 182060, 182063, 182063, 182175, 182175, 182209, 182209, 182269, 182269,
        182489, 182489, 182494, 182494, 182497, 182497, 182515, 182515, 182535, 182535, 182538, 182538,
        182557, 182557, 182786, 182786, 182798, 182798, 182909, 182909, 182953, 182953, 183081, 183081,
        183085, 183086, 183089, 183089, 183096, 183097, 183099, 183099, 183103, 183103, 183105, 183105,
        183114, 183114, 183118, 183118, 183130, 183131, 183140, 183140, 183145, 183145, 183148, 183148,
        183151, 183151, 183155, 183155, 183158, 183158, 183160, 183160, 183164, 183164, 183217, 183217,
        183231, 183232, 183246, 183246, 183382, 183382, 183391, 183391, 183541, 183542, 183549, 183549,
        183551, 183551, 183554, 183555, 183562, 183562, 183691, 183691, 183693, 183693, 183695, 183696,
        183711, 183712, 183720, 183720, 183725, 183726, 183765, 183765, 183832, 183832, 183834, 183834,
        183843, 183843, 183846, 183846, 183850, 183850, 183932, 183932, 183944, 183944, 183955, 183955,
    )
    // 替换前的原始系统字体对象：某些 ROM 上反射换 DEFAULT 不生效（系统字体走私有路径），
    // 需要 [applyAll] 遍历视图树，把仍在使用这些原始字体的 TextView 强制换成 MiSans。
    private var originalDefault: Typeface? = null
    private var originalDefaultBold: Typeface? = null
    private val originalFamilies = HashMap<String, Typeface>()

    private fun dir(): File? = Utils.application.safeCacheDir?.let { File(it, "fonts") }
    fun regularFile(): File? = dir()?.let { File(it, "MiSans-Regular.ttf") }
    fun mediumFile(): File? = dir()?.let { File(it, "MiSans-Medium.ttf") }
    fun boldFile(): File? = dir()?.let { File(it, "MiSans-Bold.ttf") }
    fun unifontFile(): File? = dir()?.let { File(it, "unifont-17.0.05.otf") }

    // installed() 结果缓存：字体文件在 /sdcard 上，每次 isFile() 都是一次 stat 系统调用。
    // 聊天列表滚动、消息绑定、视图树遍历都会高频调用它；3 秒内复用上次结果，文件被系统
    // 清缓存等外部变化最多 3 秒后生效（下载/清除路径会主动失效，立即生效）。
    private const val INSTALLED_TTL_MS = 3000L
    @Volatile private var installedCheckedAt = 0L
    @Volatile private var installedCache = false

    fun installed(): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (now - installedCheckedAt < INSTALLED_TTL_MS) return installedCache
        val v = Settings.fontPackEnabled.value &&
            regularFile()?.isFile == true && unifontFile()?.isFile == true
        installedCache = v
        installedCheckedAt = now
        return v
    }

    private fun invalidateInstalled() {
        installedCheckedAt = 0L
    }

    /** 设置页状态文案。 */
    fun statusText(): String {
        val mi = regularFile()?.isFile == true
        val m = mediumFile()?.isFile == true
        val b = boldFile()?.isFile == true
        val uni = unifontFile()?.isFile == true
        return when {
            mi && m && b && uni -> "已下载 MiSans(Regular+Medium+Bold) + Unifont；开启开关并重启应用后全部界面生效"
            mi && b && uni -> "已下载 MiSans(Regular+Bold，缺 Medium) + Unifont；中等字重将回退为常规"
            mi || m || b || uni -> "字体包不完整（MiSans=$mi Medium=$m Bold=$b Unifont=$uni），请重新下载"
            else -> "未下载（官方源：小米 hyperos.mi.com + GNU 镜像，共约 16MB）"
        }
    }

    /** 启动时调用：已下载且启用则替换进程内默认字体（只影响之后创建的视图）。 */
    fun applyDefaults() {
        invalidateInstalled()
        if (!installed()) return
        runCatching {
            originalDefault = Typeface.DEFAULT
            originalDefaultBold = Typeface.DEFAULT_BOLD
            val mi = Typeface.createFromFile(regularFile())
            val medium = mediumFile()?.takeIf { it.isFile }?.let { Typeface.createFromFile(it) }
            val bold = Typeface.createFromFile(boldFile())
            miTypeface = mi
            mediumTypeface = medium
            boldTypeface = bold
            uniTypeface = Typeface.createFromFile(unifontFile())
            // 自检覆盖表（构建期生成，理应正确；异常时便于从日志定位）
            Utils.log("FontPack: miCovers 中=${miCovers(0x4E2D)} ᗜ=${miCovers(0x15DC)} 𠂇=${miCovers(0x20087)}")

            runCatching {
                val f = Typeface::class.java.getDeclaredField("sSystemFontMap")
                f.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                (f.get(null) as? HashMap<String, Typeface>)?.let { map ->
                    originalFamilies.clear()
                    originalFamilies.putAll(map)
                    // 基础字重：常规文字映射到用户选择的字重（常规/中等/粗体）。
                    val base = when (Settings.fontPackWeight.value) {
                        2 -> bold
                        1 -> medium ?: mi
                        else -> mi
                    }
                    for (k in listOf(
                        "sans-serif", "sans-serif-light",
                        "sans-serif-thin", "sans-serif-condensed", "sans-serif-condensed-light",
                    )) if (map.containsKey(k)) map[k] = base
                    if (map.containsKey("sans-serif-medium")) map["sans-serif-medium"] = medium ?: base
                    if (map.containsKey("sans-serif-bold")) map["sans-serif-bold"] = bold
                }
            }
            val base = when (Settings.fontPackWeight.value) {
                2 -> bold
                1 -> medium ?: mi
                else -> mi
            }
            setStatic(Typeface::class.java, "DEFAULT", base)
            setStatic(Typeface::class.java, "DEFAULT_BOLD", bold)
            runCatching {
                val f = Typeface::class.java.getDeclaredField("sDefaults")
                f.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                (f.get(null) as? SparseArray<Typeface>)?.let { arr ->
                    arr.put(0, base)
                    arr.put(1, bold)
                }
            }
            Utils.log("FontPack: defaults applied (MiSans weight=${Settings.fontPackWeight.value} + Unifont fallback)")
        }.onFailure { Utils.log("FontPack: applyDefaults failed: $it") }
    }

    /**
     * 遍历视图树，把仍在使用原始系统字体（默认/加粗/各 sans-serif 家族）的 TextView
     * 强制换成 MiSans。在页面内容构建完成（或 onResume）后调用，保证在反射替换
     * 不生效的 ROM 上也能全部生效。
     */
    fun applyAll(root: View) {
        if (!installed()) return
        val mi = miTypeface ?: return
        val medium = mediumTypeface ?: mi
        val bold = boldTypeface ?: mi
        val base = when (Settings.fontPackWeight.value) {
            2 -> bold
            1 -> medium
            else -> mi
        }
        if (root is TextView) {
            val text = root.text
            if (text.isNullOrEmpty()) {
                // 空文本先不动：内核可能在绑定/刷新时再写入含生僻字的内容，
                // 提前换成 MiSans 字型会把系统兜底链断掉（关键界面在绑定处另行兜底）。
            } else {
                // 单次扫描：全部覆盖直接换 MiSans 字型；否则逐字兜底 span（不重复扫描）。
                val first = firstMissing(text)
                if (first < 0) {
                    val cur = root.typeface
                    val target = when {
                        cur == null || cur == originalDefault || cur == Typeface.DEFAULT -> base
                        cur == originalDefaultBold || cur == Typeface.DEFAULT_BOLD -> bold
                        else -> originalFamilies.entries.firstOrNull { it.value == cur }?.let {
                            when {
                                it.key.contains("bold") -> bold
                                it.key.contains("medium") -> medium
                                else -> base
                            }
                        }
                    }
                    if (target != null && cur != target) root.typeface = target
                } else {
                    val uni = uniTypeface
                    if (uni != null) root.text = buildSpans(text, first, mi, uni)
                }
            }
        }
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) applyAll(root.getChildAt(i))
        }
    }

    /** MiSans 是否覆盖 [text] 的全部码位。 */
    fun coversAll(text: CharSequence): Boolean = firstMissing(text) < 0

    /** 单次扫描：返回首个 MiSans 未覆盖码位所在的 char 索引，全部覆盖返回 -1。
     *  全 BMP 文本（中文/ASCII 等最常见情况）走逐 char 位图内联快路径，零分配。 */
    private fun firstMissing(text: CharSequence): Int {
        val bmp = miBmp
        val n = text.length
        var i = 0
        while (i < n) {
            val c = text[i]
            if (c >= '\uD800') break
            val cp = c.toInt()
            val b = bmp[cp ushr 3]
            if (((b.toInt() ushr (cp and 7)) and 1) == 0) return i
            i++
        }
        while (i < n) {
            val cp = Character.codePointAt(text, i)
            if (!miCovers(cp)) return i
            i += Character.charCount(cp)
        }
        return -1
    }

    /** 码位覆盖查询：BMP 用位图 O(1)；BMP 之外用构建期生成的升序区间二分 O(log 180)。 */
    private fun miCovers(cp: Int): Boolean {
        if (cp < 0) return false
        if (cp <= 0xFFFF) {
            val b = miBmp[cp ushr 3]
            return (b.toInt() ushr (cp and 7)) and 1 == 1
        }
        var lo = 0
        var hi = miExtRanges.size / 2 - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val s = miExtRanges[mid shl 1]
            if (cp < s) hi = mid - 1
            else {
                val e = miExtRanges[(mid shl 1) + 1]
                if (cp > e) lo = mid + 1
                else return true
            }
        }
        return false
    }

    private fun setStatic(cls: Class<*>, name: String, value: Any) {
        val f = cls.getDeclaredField(name)
        f.isAccessible = true
        f.set(null, value)
    }

    /**
     * 逐字符字体兜底：MiSans 覆盖的码位用 MiSans，其余用 Unifont。返回 Spannable，
     * 可直接 setText（叠加在已有 span 之上）。未安装/未启用时原样返回。
     */
    fun fallback(text: CharSequence): CharSequence {
        if (!installed() || text.isEmpty()) return text
        // 幂等：已包过兜底 span 的直接返回，避免重复包装叠加。
        if (text is Spanned && text.getSpans(0, text.length, FontSpan::class.java).isNotEmpty()) return text
        val mi = miTypeface ?: return text
        val uni = uniTypeface ?: return text
        // 单次扫描：全部被 MiSans 覆盖则原样返回（常见情况零分配）。
        val first = firstMissing(text)
        if (first < 0) return text
        return buildSpans(text, first, mi, uni)
    }

    /** 从首个缺字位置起构建逐字 span；[0, first) 前缀已确认全被 MiSans 覆盖，直接跳过。 */
    private fun buildSpans(text: CharSequence, first: Int, mi: Typeface, uni: Typeface): Spannable {
        val sb = SpannableStringBuilder(text)
        var start = 0
        var cur = first
        var inMi = true
        while (cur < text.length) {
            val cp = Character.codePointAt(text, cur)
            val covered = miCovers(cp)
            if (covered != inMi) {
                if (cur > start) {
                    sb.setSpan(FontSpan(if (inMi) mi else uni), start, cur, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                start = cur
                inMi = covered
            }
            cur += Character.charCount(cp)
        }
        if (cur > start) {
            sb.setSpan(FontSpan(if (inMi) mi else uni), start, cur, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return sb
    }

    /** 后台下载两个字体文件，[onStatus] 在主线程回报文字状态（进度/完成/失败）。 */
    fun download(onStatus: (String) -> Unit) {
        if (busy) return
        busy = true
        thread {
            var ok = false
            var last = ""
            try {
                val dir = dir() ?: throw IllegalStateException("无缓存目录")
                dir.mkdirs()
                val reg = File(dir, "MiSans-Regular.ttf")
                val medium = File(dir, "MiSans-Medium.ttf")
                val bold = File(dir, "MiSans-Bold.ttf")
                val uni = File(dir, "unifont-17.0.05.otf")

                report(onStatus, "下载 MiSans…")
                fetchZipEntry(MI_ZIP_URL, MI_REGULAR, reg) { done, total ->
                    val p = if (total > 0) done * 100 / total else 0
                    val t = "下载 MiSans $p%"
                    if (t != last) { last = t; report(onStatus, t) }
                }
                report(onStatus, "下载 MiSans Medium…")
                runCatching {
                    fetchZipEntry(MI_ZIP_URL, MI_MEDIUM, medium) { done, total ->
                        val p = if (total > 0) done * 100 / total else 0
                        val t = "下载 MiSans Medium $p%"
                        if (t != last) { last = t; report(onStatus, t) }
                    }
                }.onFailure {
                    Utils.log("FontPack: MiSans Medium 下载失败（可选，继续）: $it")
                    runCatching { medium.delete() }
                }
                report(onStatus, "下载 MiSans Bold…")
                fetchZipEntry(MI_ZIP_URL, MI_BOLD, bold) { done, total ->
                    val p = if (total > 0) done * 100 / total else 0
                    val t = "下载 MiSans Bold $p%"
                    if (t != last) { last = t; report(onStatus, t) }
                }
                report(onStatus, "下载 Unifont…")
                var uniOk = false
                for (u in listOf(UNI_URL) + UNI_FALLBACKS) {
                    try {
                        downloadDirect(u, uni) { done, total ->
                            val p = if (total > 0) done * 100 / total else 0
                            val t = "下载 Unifont $p%"
                            if (t != last) { last = t; report(onStatus, t) }
                        }
                        uniOk = true
                        break
                    } catch (t: Throwable) {
                        Utils.log("FontPack: Unifont 源失败 $u: $t")
                        runCatching { uni.delete() }
                    }
                }
                if (!uniOk) throw IllegalStateException("所有 Unifont 源都不可用")

                if (reg.length() > 0 && bold.length() > 0 && uni.length() > 0) {
                    applyDefaults()
                    ok = true
                }
            } catch (t: Throwable) {
                Utils.log("FontPack: download failed: $t")
                last = "下载失败：${t.message ?: t.javaClass.simpleName}"
            } finally {
                busy = false
                invalidateInstalled()
                report(onStatus, if (ok) "已安装（重启应用后全部界面生效）" else last.ifBlank { "下载失败" })
            }
        }
    }

    /** 删除已下载字体（进程内已替换的默认字体需重启应用才恢复）。 */
    fun clear() {
        dir()?.listFiles()?.forEach { runCatching { it.delete() } }
        miTypeface = null
        mediumTypeface = null
        boldTypeface = null
        uniTypeface = null
        invalidateInstalled()
    }

    private fun report(onStatus: (String) -> Unit, s: String) {
        runOnUi { onStatus(s) }
    }

    // ===== HTTP Range 工具 =====

    private fun openRange(url: String, start: Long, end: Long?): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20000
            readTimeout = 60000
            // Android 4.4 的连接池对 Range 请求有已知怪癖（会串响应/丢 Range），
            // 每个请求强制独立连接，宁可慢一点也要拿到正确的字节。
            setRequestProperty("Connection", "close")
            // 只用正区间（Android 4.4 会把 `bytes=-N` 后缀区间弄丢，导致服务器回整包）
            setRequestProperty(
                "Range",
                if (end == null) "bytes=$start-" else "bytes=$start-$end",
            )
        }

    private fun hex16(b: ByteArray, off: Int): String {
        if (b.isEmpty()) return "<empty>"
        val from = off.coerceIn(0, b.size)
        val to = (off + 16).coerceAtMost(b.size)
        val sb = StringBuilder()
        for (i in from until to) sb.append("%02x".format(b[i].toInt() and 0xFF))
        return sb.toString()
    }

    /** 探测远端文件总大小（Range: bytes=0-0 → 206 Content-Range 里的 total）。 */
    private fun totalSize(url: String): Long {
        val c = openRange(url, 0, 0)
        try {
            val code = c.responseCode
            if (code != 206) throw IllegalStateException("服务器不支持 Range（HTTP $code）")
            val cr = c.getHeaderField("Content-Range")
            val total = cr?.substringAfter('/')?.toLongOrNull()
            Utils.log("FontPack: totalSize $url -> $cr = $total")
            return total
                ?: throw IllegalStateException("缺少 Content-Range")
        } finally {
            c.disconnect()
        }
    }

    private fun range(url: String, start: Long, end: Long): ByteArray {
        val c = openRange(url, start, end)
        try {
            if (c.responseCode != 206) {
                Utils.log("FontPack: range fail $start-$end -> HTTP ${c.responseCode}")
                throw IllegalStateException("Range 请求失败 HTTP ${c.responseCode}")
            }
            val len = c.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L
            if (len > 4 * 1024 * 1024L) throw IllegalStateException("Range 响应过大 $len")
            return c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    /** 从远程 zip 中只拉取 [entryPath] 这一个条目（官方 MiSans.zip 227MB 时只需约 5.4MB）。 */
    private fun fetchZipEntry(
        zipUrl: String,
        entryPath: String,
        out: File,
        onProgress: (Long, Long) -> Unit,
    ) {
        val total = totalSize(zipUrl)
        val tailStart = (total - 65536L).coerceAtLeast(0L)
        Utils.log("FontPack: zip tail range $tailStart-${total - 1} (total=$total)")
        val tail = range(zipUrl, tailStart, total - 1)
        Utils.log("FontPack: tail size=${tail.size} first16=${hex16(tail, 0)} last16=${hex16(tail, tail.size - 16)}")
        // 找 EOCD：必须带 0 注释落在数据末尾（atEnd），且字段值合理，才认定为真 EOCD。
        // Android 4.4 偶发响应错位时，扫描会先碰到假签名——校验能直接识破并继续找。
        var eocd = -1
        var eocdEntries = 0
        var eocdCdSize = 0L
        var eocdCdOff = 0L
        for (i in tail.size - 22 downTo 0) {
            if (tail[i] == 0x50.toByte() && tail[i + 1] == 0x4B.toByte() &&
                tail[i + 2] == 0x05.toByte() && tail[i + 3] == 0x06.toByte()
            ) {
                val entries = u16(tail, i + 10)
                val cdSize = u32(tail, i + 12)
                val cdOff = u32(tail, i + 16)
                val comment = u16(tail, i + 20)
                val atEnd = i + 22 + comment == tail.size
                val plausible = entries in 1..2000 && cdSize in 1..(32L * 1024 * 1024) &&
                    cdOff in 0 until total && cdOff + cdSize <= total
                Utils.log(
                    "FontPack: EOCD cand @$i entries=$entries cdSize=$cdSize cdOff=$cdOff " +
                        "comment=$comment atEnd=$atEnd plausible=$plausible",
                )
                if (atEnd && plausible) {
                    eocd = i
                    eocdEntries = entries
                    eocdCdSize = cdSize
                    eocdCdOff = cdOff
                    break
                }
            }
        }
        if (eocd < 0) throw IllegalStateException(
            "zip EOCD 缺失/异常 (tail=${tail.size}B, 头${hex16(tail, 0)} 尾${hex16(tail, tail.size - 16)})",
        )
        val entryCount = eocdEntries
        val cdSize = eocdCdSize
        val cdOff = eocdCdOff
        Utils.log("FontPack: EOCD ok @$eocd entries=$entryCount cdSize=$cdSize cdOff=$cdOff")

        val cd = range(zipUrl, cdOff, cdOff + cdSize - 1)
        Utils.log("FontPack: cd range ${cdOff}-${cdOff + cdSize - 1} got ${cd.size}B")
        var pos = 0
        var found: LongArray? = null // comp, csize, usize, lho
        while (pos + 46 <= cd.size) {
            if (cd[pos] != 'P'.code.toByte() || cd[pos + 1] != 'K'.code.toByte()) break
            val nlen = u16(cd, pos + 28)
            val elen = u16(cd, pos + 30)
            val clen = u16(cd, pos + 32)
            val name = String(cd, pos + 46, nlen, Charsets.UTF_8)
            if (name == entryPath) {
                found = longArrayOf(
                    u16(cd, pos + 10).toLong(), // compression
                    u32(cd, pos + 20), // compressed size
                    u32(cd, pos + 24), // uncompressed size
                    u32(cd, pos + 42), // local header offset
                )
                break
            }
            pos += 46 + nlen + elen + clen
        }
        val (comp, csize, usize, lho) = found ?: throw IllegalStateException("zip 内找不到 $entryPath")

        val lh = range(zipUrl, lho, lho + 29)
        val nameLen = u16(lh, 26)
        val extraLen = u16(lh, 28)
        val dataStart = lho + 30 + nameLen + extraLen

        out.outputStream().use { os ->
            var done = 0L
            val step = 512 * 1024L
            var s = dataStart
            while (s < dataStart + csize) {
                val e = minOf(s + step - 1, dataStart + csize - 1)
                val bytes = range(zipUrl, s, e)
                os.write(bytes)
                done += bytes.size
                onProgress(done, csize)
                s = e + 1
            }
        }
        if (comp == 8L) {
            // deflate：读回再解压写回
            val raw = out.readBytes()
            val inflated = inflate(raw, usize.toInt())
            out.writeBytes(inflated)
        }
    }

    /** 直接下载整个文件（带 Content-Length 进度）。 */
    private fun downloadDirect(url: String, out: File, onProgress: (Long, Long) -> Unit) {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20000
            readTimeout = 60000
            // 镜像站会拦默认/空 UA（返回 403），带浏览器 UA 才能下到
            setRequestProperty("User-Agent", BROWSER_UA)
            setRequestProperty("Connection", "close")
        }
        try {
            val code = c.responseCode
            if (code != 200) throw IllegalStateException("HTTP $code")
            val total = c.getHeaderField("Content-Length")?.toLongOrNull() ?: 0L
            val input: InputStream = c.inputStream
            out.outputStream().use { os ->
                val buf = ByteArray(64 * 1024)
                var done = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    os.write(buf, 0, n)
                    done += n
                    onProgress(done, total)
                }
            }
        } finally {
            c.disconnect()
        }
    }

    private fun inflate(data: ByteArray, expected: Int): ByteArray {
        val inf = Inflater(true)
        inf.setInput(data)
        val out = ByteArray(expected)
        val n = inf.inflate(out)
        inf.end()
        if (n != expected) throw IllegalStateException("deflate 解压长度不符 $n/$expected")
        return out
    }

    private fun u16(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    private fun u32(b: ByteArray, off: Int): Long =
        (b[off].toLong() and 0xFF) or ((b[off + 1].toLong() and 0xFF) shl 8) or
            ((b[off + 2].toLong() and 0xFF) shl 16) or ((b[off + 3].toLong() and 0xFF) shl 24)

    private class FontSpan(private val tf: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(p: TextPaint) { p.typeface = tf }
        override fun updateMeasureState(p: TextPaint) { p.typeface = tf }
    }
}
