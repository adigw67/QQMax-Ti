package momoi.mod.qqpro.hook.aio_cell

import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.net.toUri
import com.tencent.watch.aio_impl.ui.widget.AIOCellGroupWidget
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.api.Http
import momoi.mod.qqpro.confirmOpenUrl
import loadPicUrl
import momoi.mod.qqpro.lib.FILL
import momoi.mod.qqpro.lib.WRAP
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.firstUrl
import momoi.mod.qqpro.util.runOnUi
import momoi.mod.qqpro.util.withScheme
import momoi.mod.qqpro.warpOnce
import java.util.WeakHashMap

/**
 * Client-side link preview shown below a text message.
 *
 * For the first URL in a message we fetch the page and pull OpenGraph metadata
 * (title/description/image/site_name/icon, falling back to <title> and the host).
 * A header row with the site favicon + display name is shown above the metadata.
 *
 * Keyed by the [AIOCellGroupWidget] cell (not the inner TextView) so RecyclerView
 * reuse is handled the same way the other cell hooks do it: each cell owns one
 * preview card that is updated/hidden on every rebind, so it never shows another
 * message's preview while scrolling. Resolved results are cached by URL in [cache]
 * (value null = "tried, nothing usable") so each link is only fetched once.
 */
object LinkPreview {
    private val cards = WeakHashMap<AIOCellGroupWidget, Card>()
    private val cache = HashMap<String, Og?>()
    private const val RESOLVING = "解析中…"

    /** Hide any preview card on this cell (used when a special-message hook owns it). */
    fun hide(widget: AIOCellGroupWidget) {
        cards[widget]?.root?.visibility = View.GONE
    }

    fun bind(widget: AIOCellGroupWidget) {
        val content = widget.getContentWidget<View>() as? TextView
        val url = if (Settings.enableLinkPreview.value && content != null) {
            firstUrl(content.text)
        } else null

        if (url == null) {
            cards[widget]?.root?.visibility = View.GONE
            return
        }
        val card = cards.getOrPut(widget) {
            val c = Card(content!!.context)
            // Wrap the message text in a vertical column and append the preview below it.
            // warpOnce: reuse an existing wrapper (e.g. one a special-message hook already made)
            // instead of nesting a second LinearLayout, which used to leak a weighted wrapper.
            val warp = content.warpOnce()
            warp.addView(c.root, LinearLayout.LayoutParams(FILL, WRAP).apply {
                topMargin = dp(content.context, 4)
            })
            c
        }
        // Keep the message text WRAP so IT decides the bubble width (matches AIOCell.i()'s reset).
        // The card below is MATCH_PARENT, so LinearLayout re-measures it to exactly the text's wrap
        // width — the preview fills the text width instead of dictating a wider/narrower bubble.
        (content!!.layoutParams as? LinearLayout.LayoutParams)?.also {
            it.width = WRAP
            it.height = WRAP
            it.weight = 0f
        } ?: run { content.layoutParams = LinearLayout.LayoutParams(WRAP, WRAP) }
        card.root.visibility = View.VISIBLE
        card.show(url)
    }

    /**
     * Add a preview below a freshly-built text view in the merged-forward / chat-history
     * detail view. Those rows are rebuilt from scratch on every bind (the container is
     * cleared), so a new card is created each time; the URL [cache] still avoids refetching.
     */
    fun bindHistory(container: ViewGroup, text: CharSequence) {
        if (!Settings.enableLinkPreview.value) return
        val url = firstUrl(text) ?: return
        val card = Card(container.context)
        // The history bubble is WRAP_CONTENT, so MATCH_PARENT would collapse the card to the
        // text width. Give it an explicit width so the preview stays a consistent, readable
        // size regardless of how long the message text is.
        val width = (container.resources.displayMetrics.widthPixels * 0.62f).toInt()
        container.addView(card.root, LinearLayout.LayoutParams(width, WRAP).apply {
            topMargin = dp(container.context, 4)
        })
        card.show(url)
    }

    private fun dp(ctx: android.content.Context, v: Int) =
        (v * ctx.resources.displayMetrics.density).toInt()

    /** One preview card; built standalone and attached by the caller. */
    private class Card(ctx: android.content.Context) {
        val root: LinearLayout
        private val favicon: ImageView
        private val site: TextView
        private val title: TextView
        private val desc: TextView
        private val image: ImageView
        private var boundUrl: String? = null

        init {
            root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(8), dp(6), dp(8), dp(6))
                background = GradientDrawable().apply {
                    setColor(0x33_000000)
                    cornerRadius = dp(8).toFloat()
                }
                setOnClickListener { v ->
                    val u = boundUrl ?: return@setOnClickListener
                    if (Settings.confirmOpenLink.value) v.confirmOpenUrl(u)
                    else Utils.openUrl(u)
                }
            }

            val header = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            favicon = ImageView(ctx).apply {
                maxHeight = dp(64)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            header.addView(favicon, LinearLayout.LayoutParams(dp(14), dp(14)).apply {
                rightMargin = dp(5)
            })
            site = TextView(ctx).apply {
                textSize = 11f
                setTextColor(M3.onSurfaceVariant)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
            header.addView(site, LinearLayout.LayoutParams(0, WRAP, 1f))
            root.addView(header, LinearLayout.LayoutParams(FILL, WRAP))

            title = TextView(ctx).apply {
                textSize = 12f
                setTextColor(M3.onSurface)
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
            }
            root.addView(title, LinearLayout.LayoutParams(FILL, WRAP).apply { topMargin = dp(3) })

            desc = TextView(ctx).apply {
                textSize = 11f
                setTextColor(M3.onSurfaceVariant)
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
            }
            root.addView(desc, LinearLayout.LayoutParams(FILL, WRAP).apply { topMargin = dp(2) })

            image = ImageView(ctx).apply {
                maxHeight = dp(140)
                adjustViewBounds = true
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            root.addView(image, LinearLayout.LayoutParams(FILL, WRAP).apply { topMargin = dp(4) })
        }

        fun show(url: String) {
            // Known-unresolvable (cached null): keep the card hidden even though the
            // caller just set it VISIBLE on rebind.
            if (cache.containsKey(url) && cache[url] == null) {
                boundUrl = url
                render(url, null)
                return
            }
            if (boundUrl == url) return // already rendered for this url; avoid flicker
            boundUrl = url

            // Immediate header from the URL's host; metadata fills in after fetch.
            val host = runCatching { url.withScheme().toUri().host }.getOrNull() ?: url
            site.text = host
            favicon.visibility = View.GONE
            title.text = RESOLVING
            title.visibility = View.VISIBLE
            desc.visibility = View.GONE
            image.visibility = View.GONE

            cache[url]?.let { render(url, it); return }
            if (cache.containsKey(url)) { render(url, null); return }

            LinkPreview.resolve(url) { og ->
                cache[url] = og
                runOnUi { if (boundUrl == url) render(url, og) }
            }
        }

        private fun render(url: String, og: Og?) {
            if (og == null) {
                // Nothing resolved: hide the whole card rather than leaving a bare
                // host header / spinner behind.
                root.visibility = View.GONE
                return
            }
            site.text = og.siteName
            if (og.iconUrl != null) {
                favicon.visibility = View.VISIBLE
                favicon.loadPicUrl(og.iconUrl, cacheFileName = "fav${og.iconUrl.hashCode()}")
            } else favicon.visibility = View.GONE

            if (og.title != null) {
                title.visibility = View.VISIBLE
                title.text = og.title
            } else title.visibility = View.GONE

            if (og.description != null) {
                desc.visibility = View.VISIBLE
                desc.text = og.description
            } else desc.visibility = View.GONE

            if (og.imageUrl != null) {
                image.visibility = View.VISIBLE
                image.loadPicUrl(og.imageUrl, cacheFileName = "og${og.imageUrl.hashCode()}")
            } else image.visibility = View.GONE
        }

        private fun dp(v: Int) =
            (v * Utils.application.resources.displayMetrics.density).toInt()
    }

    private class Og(
        val siteName: String,
        val title: String?,
        val description: String?,
        val imageUrl: String?,
        val iconUrl: String?,
    )

    private fun resolve(url: String, callback: (Og?) -> Unit) {
        val full = url.withScheme()
        // Http runs on a background thread; failures arrive as an "Error:"/"HTTP error:" string.
        Http.get(full) { html ->
            if (html.startsWith("Error:") || html.startsWith("HTTP error:")) {
                callback(null)
                return@get
            }
            val host = runCatching { full.toUri().host }.getOrNull()
            val title = meta(html, "og:title") ?: docTitle(html)
            val desc = meta(html, "og:description")
            val siteName = meta(html, "og:site_name") ?: host ?: url
            val image = meta(html, "og:image")?.let { resolveUrl(full, it) }
            val icon = iconLink(html)?.let { resolveUrl(full, it) }
                ?: host?.let { "https://$it/favicon.ico" }

            // If we got literally nothing useful, report unresolvable.
            if (title == null && desc == null && image == null) {
                callback(null)
            } else {
                callback(Og(siteName, title, desc, image, icon))
            }
        }
    }

    private fun meta(html: String, prop: String): String? {
        val after = Regex(
            """<meta[^>]+(?:property|name)\s*=\s*["']$prop["'][^>]*\bcontent\s*=\s*["']([^"']*)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)
        if (after != null) return unescape(after).ifBlank { null }
        val before = Regex(
            """<meta[^>]+\bcontent\s*=\s*["']([^"']*)["'][^>]*(?:property|name)\s*=\s*["']$prop["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1)
        return before?.let { unescape(it).ifBlank { null } }
    }

    private fun docTitle(html: String): String? =
        Regex("""<title[^>]*>([^<]*)</title>""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.let { unescape(it).ifBlank { null } }

    /** href of the first <link rel="...icon..."> (skipping svg which can't be decoded). */
    private fun iconLink(html: String): String? {
        Regex("""<link[^>]+rel\s*=\s*["'][^"']*icon[^"']*["'][^>]*>""", RegexOption.IGNORE_CASE)
            .findAll(html).forEach { m ->
                val href = Regex("""href\s*=\s*["']([^"']*)["']""", RegexOption.IGNORE_CASE)
                    .find(m.value)?.groupValues?.get(1)
                if (href != null && !href.endsWith(".svg", ignoreCase = true)) return href
            }
        return null
    }

    /** Resolve a possibly-relative [href] against the page [base]. */
    private fun resolveUrl(base: String, href: String): String {
        if (href.startsWith("http://") || href.startsWith("https://")) return href
        val uri = runCatching { base.toUri() }.getOrNull() ?: return href
        val scheme = uri.scheme ?: "https"
        val host = uri.host ?: return href
        return when {
            href.startsWith("//") -> "$scheme:$href"
            href.startsWith("/") -> "$scheme://$host$href"
            else -> "$scheme://$host/$href"
        }
    }

    private val numericEntity = Regex("""&#(x?)([0-9a-fA-F]+);""")

    private fun unescape(s: String): String {
        // Decode numeric character references first (&#NNNN; decimal and &#xNNNN; hex). Many pages
        // entity-encode their whole <title>/og text as numeric refs (e.g. 163.com encodes every CJK
        // char), which previously showed up verbatim as "&#x6211;&#x7684;…" garbage.
        val decoded = numericEntity.replace(s) { m ->
            val code = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
            if (code != null && code in 0..0x10FFFF) String(Character.toChars(code)) else m.value
        }
        return decoded
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&#x27;", "'")
            .replace("&nbsp;", " ")
            .trim()
    }
}
