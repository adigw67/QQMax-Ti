package momoi.mod.qqpro.hook

import android.annotation.SuppressLint
import android.graphics.Typeface
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.TextPaint
import android.text.style.MetricAffectingSpan
import android.util.SparseArray
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.safeCacheDir
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.runOnUi
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
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
 *    这里用 HTTP Range 只拉 zip 里的 MiSans-Regular.ttf / MiSans-Bold.ttf 两个条目，
 *    实际约 11MB，不解压整包）
 *  - Unifont：GNU 官方镜像（unifoundry.com / ftp.gnu.org 的 TLS 配置在手表上握手被拒，
 *    清华镜像在手表上同样握手异常；阿里镜像 TLS 可用但要求浏览器 UA，否则 403。
 *    所以主源用阿里（带 UA）+ 清华/官方源兜底，文件与 ftp.gnu.org 字节一致）
 */
object FontPack {
    private const val MI_ZIP_URL = "https://hyperos.mi.com/font-download/MiSans.zip"
    private const val MI_REGULAR = "MiSans/ttf/MiSans-Regular.ttf"
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
    private var boldTypeface: Typeface? = null
    private var uniTypeface: Typeface? = null
    private var miCoverage: CmapCoverage? = null

    private fun dir(): File? = Utils.application.safeCacheDir?.let { File(it, "fonts") }
    fun regularFile(): File? = dir()?.let { File(it, "MiSans-Regular.ttf") }
    fun boldFile(): File? = dir()?.let { File(it, "MiSans-Bold.ttf") }
    fun unifontFile(): File? = dir()?.let { File(it, "unifont-17.0.05.otf") }

    fun installed(): Boolean =
        Settings.fontPackEnabled.value &&
            regularFile()?.isFile == true && unifontFile()?.isFile == true

    /** 设置页状态文案。 */
    fun statusText(): String {
        val mi = regularFile()?.isFile == true
        val b = boldFile()?.isFile == true
        val uni = unifontFile()?.isFile == true
        return when {
            mi && b && uni -> "已下载 MiSans(Regular+Bold) + Unifont；开启开关并重启应用后全部界面生效"
            mi || b || uni -> "字体包不完整（MiSans=$mi Bold=$b Unifont=$uni），请重新下载"
            else -> "未下载（官方源：小米 hyperos.mi.com + GNU 镜像，共约 16MB）"
        }
    }

    /** 启动时调用：已下载且启用则替换进程内默认字体（只影响之后创建的视图）。 */
    fun applyDefaults() {
        if (!installed()) return
        runCatching {
            val mi = Typeface.createFromFile(regularFile())
            val bold = Typeface.createFromFile(boldFile())
            miTypeface = mi
            boldTypeface = bold
            uniTypeface = Typeface.createFromFile(unifontFile())
            miCoverage = CmapCoverage(regularFile()!!)

            setStatic(Typeface::class.java, "DEFAULT", mi)
            setStatic(Typeface::class.java, "DEFAULT_BOLD", bold)
            runCatching {
                val f = Typeface::class.java.getDeclaredField("sDefaults")
                f.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                (f.get(null) as? SparseArray<Typeface>)?.let { arr ->
                    arr.put(0, mi)
                    arr.put(1, bold)
                }
            }
            runCatching {
                val f = Typeface::class.java.getDeclaredField("sSystemFontMap")
                f.isAccessible = true
                @Suppress("UNCHECKED_CAST")
                (f.get(null) as? HashMap<String, Typeface>)?.let { map ->
                    for (k in listOf(
                        "sans-serif", "sans-serif-medium", "sans-serif-light",
                        "sans-serif-thin", "sans-serif-condensed", "sans-serif-condensed-light",
                    )) if (map.containsKey(k)) map[k] = mi
                    if (map.containsKey("sans-serif-bold")) map["sans-serif-bold"] = bold
                }
            }
            Utils.log("FontPack: defaults applied (MiSans + Unifont fallback)")
        }.onFailure { Utils.log("FontPack: applyDefaults failed: $it") }
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
        val mi = miTypeface ?: return text
        val uni = uniTypeface ?: return text
        val cover = miCoverage ?: return text
        val sb = SpannableStringBuilder(text)
        var start = 0
        var cur = 0
        var inMi = true
        while (cur < text.length) {
            val cp = Character.codePointAt(text, cur)
            val covered = cover.has(cp)
            if (cur == 0) inMi = covered
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
                val bold = File(dir, "MiSans-Bold.ttf")
                val uni = File(dir, "unifont-17.0.05.otf")

                report(onStatus, "下载 MiSans…")
                fetchZipEntry(MI_ZIP_URL, MI_REGULAR, reg) { done, total ->
                    val p = if (total > 0) done * 100 / total else 0
                    val t = "下载 MiSans $p%"
                    if (t != last) { last = t; report(onStatus, t) }
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
                report(onStatus, if (ok) "已安装（重启应用后全部界面生效）" else last.ifBlank { "下载失败" })
            }
        }
    }

    /** 删除已下载字体（进程内已替换的默认字体需重启应用才恢复）。 */
    fun clear() {
        dir()?.listFiles()?.forEach { runCatching { it.delete() } }
        miTypeface = null
        boldTypeface = null
        uniTypeface = null
        miCoverage = null
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

    // ===== cmap 覆盖解析（判断 MiSans 是否覆盖某码位） =====

    private class CmapCoverage(file: File) {
        private val format4 = ArrayList<IntArray>()
        private val format12 = ArrayList<LongArray>()

        init { parse(file) }

        private fun parse(file: File) {
            val data = file.readBytes()
            val buf = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN)
            val numTables = buf.getShort(4).toInt() and 0xFFFF
            var cmapOff = -1
            var cmapLen = 0
            for (i in 0 until numTables) {
                val base = 12 + i * 16
                val tag = String(data, base, 4, Charsets.US_ASCII)
                if (tag == "cmap") {
                    cmapOff = buf.getInt(base + 8)
                    cmapLen = buf.getInt(base + 12)
                    break
                }
            }
            if (cmapOff < 0 || cmapLen <= 0) return
            val cmap = ByteBuffer.wrap(data, cmapOff, cmapLen).order(ByteOrder.BIG_ENDIAN)
            val numSub = cmap.getShort(2).toInt() and 0xFFFF
            // 优先 format 12（(3,10)/(0,6)），其次 format 4（(3,1)/(0,3)）
            var f12 = -1
            var f4 = -1
            for (i in 0 until numSub) {
                val base = 4 + i * 8
                val platform = cmap.getShort(base).toInt() and 0xFFFF
                val encoding = cmap.getShort(base + 2).toInt() and 0xFFFF
                val off = cmap.getInt(base + 4)
                if (f12 < 0 && ((platform == 3 && encoding == 10) || (platform == 0 && encoding == 6))) f12 = off
                if (f4 < 0 && ((platform == 3 && encoding == 1) || (platform == 0 && encoding == 3))) f4 = off
            }
            val off = if (f12 >= 0) f12 else f4
            if (off < 0) return
            val sub = ByteBuffer.wrap(data, cmapOff + off, cmapLen - off).order(ByteOrder.BIG_ENDIAN)
            val fmt = sub.getShort(0).toInt() and 0xFFFF
            when (fmt) {
                12 -> {
                    val groups = sub.getInt(12)
                    for (i in 0 until groups) {
                        val g = 16 + i * 12
                        val start = sub.getInt(g).toLong() and 0xFFFFFFFFL
                        val end = sub.getInt(g + 4).toLong() and 0xFFFFFFFFL
                        format12.add(longArrayOf(start, end))
                    }
                }
                4 -> {
                    val segX2 = sub.getShort(6).toInt() and 0xFFFF
                    val seg = segX2 / 2
                    val endBase = 14
                    val startBase = endBase + segX2 + 2
                    val deltaBase = startBase + segX2
                    for (i in 0 until seg) {
                        val start = sub.getShort(startBase + i * 2).toInt() and 0xFFFF
                        val end = sub.getShort(endBase + i * 2).toInt() and 0xFFFF
                        val delta = sub.getShort(deltaBase + i * 2).toInt()
                        if (end == 0xFFFF && start == 0xFFFF) continue
                        if (start <= end) format4.add(intArrayOf(start, end, delta))
                    }
                }
            }
        }

        fun has(cp: Int): Boolean {
            if (cp < 0) return true
            if (cp <= 0xFFFF) {
                for (s in format4) if (cp >= s[0] && cp <= s[1]) return true
            }
            for (s in format12) {
                if (cp >= s[0] && cp <= s[1]) return true
            }
            return false
        }
    }

    private class FontSpan(private val tf: Typeface) : MetricAffectingSpan() {
        override fun updateDrawState(p: TextPaint) { p.typeface = tf }
        override fun updateMeasureState(p: TextPaint) { p.typeface = tf }
    }
}
