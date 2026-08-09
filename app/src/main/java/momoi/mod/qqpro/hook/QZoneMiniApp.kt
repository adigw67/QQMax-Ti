package momoi.mod.qqpro.hook

import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.widget.TextView
import com.tencent.watch.qzone_impl.feed.model.BusinessFeedData
import com.tencent.watch.qzone_impl.frame.IAdapterHost
import com.tencent.watch.qzone_impl.frame.contentViewHolder.ContentSummeryViewHolder
import download
import downloadExecutor
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.child
import momoi.mod.qqpro.confirmOpenUrl
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.safeCacheDir
import momoi.mod.qqpro.util.Utils
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder

/**
 * 小程序卡片渲染 ([Settings.qzoneMiniAppCard]). A QZone mini-app (小程序) share is delivered to the
 * watch with NO renderable content (cellSummary null → "请在手机QQ查看" placeholder), but its
 * cellOperationInfo.busiParam carries the share open-link with a `fakeUrl` to the share landing page
 * (https://m.q.qq.com/a/s/<hash>). That page's static HTML contains the real app name, icon and
 * description (classes gotoqq-wrap__txt-appname / __img-applogo / __txt-desc). We fetch + parse it
 * and render a card (icon as a compound drawable + bold name + grey description) in place of the
 * placeholder. The mini-app itself can't run on the watch, but the card content is fully recoverable.
 *
 * @Mixin on the bind entry [ContentSummeryViewHolder.k]; the actual fetch/parse is in [QZoneMiniApp]
 * (our package). Results are cached per fakeUrl so rebinds don't refetch; the TextView's tag guards
 * against the holder being recycled to another post before the async fetch returns.
 */
@Mixin
class ContentSummeryMiniApp(viewType: Int, host: IAdapterHost) :
    ContentSummeryViewHolder(viewType, host) {
    override fun k(data: BusinessFeedData) {
        // Native (non-materialized) QZone: substitute QZone emoji codes into the summary first.
        runCatching { momoi.mod.qqpro.hook.qzone.QzoneEmoji.patchSummary(data) }
            .onFailure { Utils.log("ContentSummeryMiniApp emoji: $it") }
        super.k(data)
        QZoneMiniApp.bind(this, data)
    }
}

object QZoneMiniApp {
    private class Info(val name: String?, val icon: String?, val desc: String?)

    private val cache = java.util.concurrent.ConcurrentHashMap<String, Info>()
    private val UA = "Mozilla/5.0 (Linux; Android 12; Mobile) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
    private val NAME_RE = Regex("gotoqq-wrap__txt-appname\"[^>]*>([^<]+)<")
    private val ICON_RE = Regex("gotoqq-wrap__img-applogo\"\\s+src=\"([^\"]+)\"")
    private val DESC_RE = Regex("gotoqq-wrap__txt-desc\"[^>]*>([^<]+)<")

    fun bind(holder: ContentSummeryViewHolder, data: BusinessFeedData) {
        val tv = runCatching { holder.h() }.getOrNull() as? TextView ?: return
        bindText(tv, data)
    }

    /**
     * Render the mini-app card into an arbitrary [tv] (reused by the materialized feed card, where the
     * native ContentSummeryViewHolder no longer runs). Returns true if [data] is a mini-app share (so
     * the caller knows to keep this TextView even though the post has no normal summary text).
     */
    fun bindText(tv: TextView, data: BusinessFeedData, allowOriginalFallback: Boolean = true): Boolean {
        if (!Settings.qzoneMiniAppCard.value) return false
        // Only a placeholder post (no renderable summary) carrying a fakeUrl is a mini-app share.
        // A direct share keeps the payload in [data] (originalInfo null → falls through to data); a
        // FORWARD keeps it in originalInfo. The materialized feed renders the forwarded original in its
        // own quote box, so the main-body call passes allowOriginalFallback=false to avoid rendering
        // the same card twice (once in the body via this fallback, once in the quote).
        val t = (if (allowOriginalFallback) runCatching { data.originalInfo }.getOrNull() else null) ?: data
        if (runCatching { t.getCellSummaryV2() }.getOrNull() != null) { clear(tv); return false }
        val op = runCatching { t.cellOperationInfo }.getOrNull() ?: run { clear(tv); return false }
        val fakeUrl = extractFakeUrl(op.busiParam) ?: run { clear(tv); return false }

        tv.tag = fakeUrl
        val cached = cache[fakeUrl]
        if (cached != null) { render(tv, fakeUrl, cached); return true }
        tv.setCompoundDrawables(null, null, null, null)
        tv.text = "小程序加载中…"
        fetch(tv, fakeUrl)
        return true
    }

    private fun clear(tv: TextView) {
        if (tv.tag is String && (tv.tag as String).startsWith("http")) {
            tv.setCompoundDrawables(null, null, null, null)
            tv.setOnClickListener(null)
            tv.isClickable = false
            tv.tag = null
        }
    }

    /** busiParam value 5 = "mqqapi://microapp/open?...&fakeUrl=<(encoded) landing url>". */
    private fun extractFakeUrl(busiParam: Map<*, *>?): String? {
        val v = busiParam?.values?.firstOrNull { it is String && it.contains("fakeUrl=") } as? String ?: return null
        val raw = v.substringAfter("fakeUrl=", "").substringBefore("&")
        if (raw.isEmpty()) return null
        val url = runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)
        return url.takeIf { it.startsWith("http") }
    }

    private fun fetch(tv: TextView, fakeUrl: String) {
        downloadExecutor.execute {
            val html = httpGet(fakeUrl)
            if (html == null) {
                Utils.log("QZoneMiniApp: fetch failed $fakeUrl")
                return@execute
            }
            val info = Info(
                name = NAME_RE.find(html)?.groupValues?.get(1)?.let(::unescape),
                icon = ICON_RE.find(html)?.groupValues?.get(1),
                desc = DESC_RE.find(html)?.groupValues?.get(1)?.let(::unescape),
            )
            cache[fakeUrl] = info
            Utils.log("QZoneMiniApp: parsed name=${info.name} icon=${info.icon != null}")
            tv.post { if (tv.tag == fakeUrl) render(tv, fakeUrl, info) }
        }
    }

    private fun render(tv: TextView, fakeUrl: String, info: Info) {
        val name = info.name?.takeIf { it.isNotBlank() } ?: "小程序"
        val desc = info.desc?.takeIf { it.isNotBlank() }
        val full = if (desc == null) "📱 $name" else "📱 $name\n$desc"
        val sp = SpannableString(full)
        val nameEnd = full.indexOf('\n').let { if (it == -1) full.length else it }
        sp.setSpan(StyleSpan(Typeface.BOLD), 0, nameEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        if (desc != null) {
            sp.setSpan(RelativeSizeSpan(0.68f), nameEnd, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            sp.setSpan(ForegroundColorSpan(M3.onSurfaceVariant), nameEnd, full.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        tv.text = sp
        // Tap opens the mini-app share link — confirm first or open directly, same as chat links.
        tv.isClickable = true
        tv.setOnClickListener {
            if (Settings.confirmOpenLink.value) it.confirmOpenUrl(fakeUrl) else Utils.openUrl(fakeUrl)
        }
        info.icon?.let { loadIcon(tv, fakeUrl, it) }
    }

    private fun loadIcon(tv: TextView, fakeUrl: String, iconUrl: String) {
        val cacheDir = tv.context.safeCacheDir ?: return
        val file = cacheDir.child("miniapp_${iconUrl.hashCode()}.png")
        val apply = {
            runCatching {
                val bmp = BitmapFactory.decodeFile(file.path) ?: return@runCatching
                val size = 44.dp
                val d = BitmapDrawable(tv.resources, bmp).apply { setBounds(0, 0, size, size) }
                tv.setCompoundDrawables(d, null, null, null)
                tv.compoundDrawablePadding = 8.dp
            }
        }
        if (file.exists() && file.length() > 0) { if (tv.tag == fakeUrl) apply(); return }
        download(iconUrl, file) { ok ->
            if (ok) tv.post { if (tv.tag == fakeUrl) apply() }
        }
    }

    private fun httpGet(rawUrl: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = URL(rawUrl.replace("http://", "https://"))
            conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", UA)
            }
            if (conn.responseCode != HttpURLConnection.HTTP_OK) return null
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Utils.log("QZoneMiniApp: httpGet ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    /** Minimal HTML entity decode for the parsed name/desc. */
    private fun unescape(s: String): String = s
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").trim()
}
