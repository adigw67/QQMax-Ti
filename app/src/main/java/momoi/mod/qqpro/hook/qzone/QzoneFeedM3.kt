package momoi.mod.qqpro.hook.qzone
import momoi.mod.qqpro.lib.setClipToOutlineCompat

import android.content.Context
import android.graphics.Outline
import android.graphics.Typeface
import android.os.Build
import android.text.Spannable
import android.text.SpannableString
import android.text.TextPaint
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.scwang.smart.refresh.layout.SmartRefreshLayout
import com.tencent.watch.qzone_impl.feed.model.BusinessFeedData
import com.tencent.watch.qzone_impl.feed.model.Comment
import com.tencent.watch.qzone_impl.feed.model.User
import com.tencent.watch.qzone_impl.frame.IAdapterHost
import com.tencent.watch.qzone_impl.frame.QZoneFeedAdapter
import com.tencent.watch.qzone_impl.frame.QZoneMainFrame
import com.tencent.watch.qzone_impl.frame.QZoneMineFragment
import com.tencent.watch.qzone_impl.utils.StringUtil
import loadPicUrl
import momoi.mod.qqpro.hook.view.InlineVideoView
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.hook.QZoneMiniApp
import momoi.mod.qqpro.hook.openProfileByUin
import momoi.mod.qqpro.hook.openUserQzone
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3Progress
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.lib.material.leadingSymbol
import momoi.mod.qqpro.lib.material.symbolImage
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.linkColorResolved
import java.util.WeakHashMap

/**
 * From-scratch Material 3 QZone feed ([Settings.materializeQzone]). Rather than reparent the native
 * fragment (fragile — see the no-tree-rebuild rule), we keep the native fragment, SmartRefreshLayout
 * and feed engine and only swap the RecyclerView's adapter to our own [FeedAdapter], fed by reading
 * the native adapter's list after each native [QZoneMineFragment.O]/[QZoneMainFrame.O] data callback.
 *
 * Shared by both feed hosts (per-user [QZoneMineFragment] and the main page [QZoneMainFrame]); both
 * implement [IAdapterHost] (so `this` is the action host) and expose a public SmartRefreshLayout +
 * [QZoneFeedAdapter] field. NOT a @Mixin class — anonymous classes are fine here.
 */
object QzoneFeedM3 {

    private val adapters = WeakHashMap<Any, FeedAdapter>()

    // The most-recently installed feed's pull-refresh host, so write flows (publish / comment) can ask
    // the native engine to reload from the server — which fires O() and re-feeds our M3 adapter.
    private var activeSrl: java.lang.ref.WeakReference<SmartRefreshLayout>? = null

    /**
     * Re-bind the feed cards in place (no server reload, no re-sort, no scroll) so a card reflects an
     * edit made to its shared [BusinessFeedData] elsewhere — e.g. a comment added/deleted in the
     * comment thread. Use this instead of [refreshActiveFeed] for comment edits: a full refresh would
     * jump the reading position. Notifies all installed feed adapters; only the one holding the edited
     * post actually changes.
     */
    fun notifyFeeds() {
        runCatching { adapters.values.toList().forEach { it.notifyDataSetChanged() } }
            .onFailure { Utils.log("QzoneFeedM3.notifyFeeds: $it") }
    }

    /**
     * Trigger the native pull-to-refresh on the active feed (after a short delay so a just-posted
     * comment/feed has reached the server). Invokes the SmartRefreshLayout's stored OnRefreshListener
     * (`s0`, method `m`) — the same path as a manual pull — which reloads and fires [QZoneMainFrame.O].
     */
    fun refreshActiveFeed(delayMs: Long = 800L) {
        val srl = activeSrl?.get() ?: run { Utils.log("QzoneFeedM3.refreshActiveFeed: no active feed"); return }
        srl.postDelayed({
            runCatching {
                val listener = srl.s0 ?: run { Utils.log("QzoneFeedM3.refreshActiveFeed: no OnRefreshListener"); return@postDelayed }
                listener.m(srl)
                Utils.log("QzoneFeedM3: triggered feed refresh")
            }.onFailure { Utils.log("QzoneFeedM3.refreshActiveFeed invoke: $it") }
        }, delayMs)
    }

    fun installMine(f: QZoneMineFragment) = install(f, f as IAdapterHost, "i", "k", perUser = true)
    fun installMain(f: QZoneMainFrame) = install(f, f as IAdapterHost, "n", "o", perUser = false)
    fun feedMine(f: QZoneMineFragment) = feed(f, "k")
    fun feedMain(f: QZoneMainFrame) = feed(f, "o")

    private fun install(key: Any, host: IAdapterHost, srlField: String, adapterField: String, perUser: Boolean) {
        runCatching {
            val srl = key.javaClass.getField(srlField).get(key) as? SmartRefreshLayout ?: run {
                Utils.log("QzoneFeedM3: no SmartRefreshLayout ($srlField)"); return
            }
            val rv = (0 until srl.childCount).mapNotNull { srl.getChildAt(it) as? RecyclerView }.firstOrNull()
                ?: run { Utils.log("QzoneFeedM3: no RecyclerView in SmartRefreshLayout"); return }
            // The native window behind the list is pure black; give the feed an M3 surface.
            runCatching { srl.setBackgroundColor(M3.surface); rv.setBackgroundColor(M3.surface) }
            val adapter = FeedAdapter(host, perUser)
            adapters[key] = adapter
            activeSrl = java.lang.ref.WeakReference(srl)
            rv.adapter = adapter
            // Let the rotary encoder drive pagination: a programmatic scrollBy can't fire
            // SmartRefreshLayout's gesture-based load-more, so the crown handler calls it directly.
            momoi.mod.qqpro.hook.EncoderLoadMore.register(rv, srl)
            // Seed from any data the native adapter already holds (e.g. after a config change).
            nativeList(key, adapterField)?.let { adapter.submit(it) }
            Utils.log("QzoneFeedM3: installed M3 feed adapter (perUser=$perUser)")
        }.onFailure { Utils.log("QzoneFeedM3 install: $it") }
    }

    private fun feed(key: Any, adapterField: String) {
        runCatching {
            val list = nativeList(key, adapterField) ?: return
            adapters[key]?.submit(list)
        }.onFailure { Utils.log("QzoneFeedM3 feed: $it") }
    }

    @Suppress("UNCHECKED_CAST")
    private fun nativeList(key: Any, adapterField: String): List<BusinessFeedData>? = runCatching {
        val adapter = key.javaClass.getField(adapterField).get(key) as? QZoneFeedAdapter ?: return null
        adapter.c as? List<BusinessFeedData>
    }.getOrNull()

    // ===================================================================================

    class VH(val container: FrameLayout) : RecyclerView.ViewHolder(container)

    class FeedAdapter(val host: IAdapterHost, val perUser: Boolean) : RecyclerView.Adapter<VH>() {
        private var items: List<BusinessFeedData> = emptyList()

        fun submit(list: List<BusinessFeedData>) {
            items = ArrayList(list)
            notifyDataSetChanged()
        }

        private fun hasHeader() = perUser && items.isNotEmpty()
        private fun dataPos(pos: Int) = if (hasHeader()) pos - 1 else pos

        override fun getItemCount() = items.size + if (hasHeader()) 1 else 0

        override fun getItemViewType(position: Int) = if (hasHeader() && position == 0) 0 else 1

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val container = FrameLayout(parent.context).apply {
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }
            return VH(container)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            holder.container.removeAllViews()
            val view = runCatching {
                if (getItemViewType(position) == 0) QzoneFeedCard.buildHeader(host, items[0])
                else QzoneFeedCard.buildCard(host, items[dataPos(position)])
            }.onFailure { Utils.log("QzoneFeedM3 bind $position: $it") }.getOrNull() ?: return
            holder.container.addView(
                view,
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
            )
        }
    }
}

/** Builds the M3 feed-card and per-user profile-header views. Separate object so it can host the
 *  anonymous click lambdas the @Mixin hooks must not. */
object QzoneFeedCard {

    private fun circleAvatar(ctx: Context, sizeDp: Int): ImageView = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        maxHeight = sizeDp.dp
        setClipToOutlineCompat(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
            }
        }
    }

    fun buildHeader(host: IAdapterHost, data: BusinessFeedData): View {
        val ctx = host.requireContext()
        val user: User? = runCatching { data.user }.getOrNull()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = M3.rounded(M3.surfaceContainer, M3.radiusLg)
            setPadding(16.dp, 20.dp, 16.dp, 18.dp)
        }
        // Tap the header → open this user's profile card.
        runCatching {
            val uin = user?.uin ?: 0L
            if (uin > 0L) {
                card.isClickable = true
                card.setOnClickListener { v ->
                    runCatching { v.openProfileByUin(uin) }
                        .onFailure { Utils.log("QzoneHeader openProfile uin=$uin: $it") }
                }
            }
        }
        val avatar = circleAvatar(ctx, 72)
        card.addView(avatar, LinearLayout.LayoutParams(72.dp, 72.dp))
        runCatching {
            val uin = user?.uin ?: 0L
            avatar.loadPicUrl(user?.avatarPath?.takeIf { it.isNotEmpty() } ?: QzoneActions.avatarUrl(uin), "qzhdr_$uin")
        }
        card.addView(TextView(ctx).apply {
            text = user?.nickName ?: "TA"
            setTextColor(M3.onSurface)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = 10.dp })
        user?.qzoneDesc?.takeIf { it.isNotBlank() }?.let { sig ->
            card.addView(TextView(ctx).apply {
                text = sig
                setTextColor(M3.onSurfaceVariant)
                textSize = 12f
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 6.dp })
        }
        val stats = StringBuilder()
        user?.let {
            if (it.fansCount > 0) stats.append("粉丝 ${it.fansCount}")
            if (it.visitorCount > 0) { if (stats.isNotEmpty()) stats.append("  ·  "); stats.append("访客 ${it.visitorCount}") }
        }
        if (stats.isNotEmpty()) card.addView(TextView(ctx).apply {
            text = stats.toString()
            setTextColor(M3.onSurfaceTip)
            textSize = 12f
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = 8.dp })
        return wrapMargins(card)
    }

    fun buildCard(host: IAdapterHost, data: BusinessFeedData): View {
        val ctx = host.requireContext()
        dumpRepostDebug(data)
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = M3.rounded(M3.surfaceContainer, M3.radiusLg)
            setPadding(12.dp, 12.dp, 12.dp, 8.dp)
        }
        val user: User? = runCatching { data.user }.getOrNull()

        // --- header: avatar | nick / time (tap avatar/name → that user's space) ---
        val header = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val avatar = circleAvatar(ctx, 31)
        header.addView(avatar, LinearLayout.LayoutParams(31.dp, 31.dp))
        runCatching {
            val uin = user?.uin ?: 0L
            avatar.loadPicUrl(user?.avatarPath?.takeIf { it.isNotEmpty() } ?: QzoneActions.avatarUrl(uin), "qzav_$uin")
        }
        val openSpace = View.OnClickListener {
            val uin = user?.uin ?: 0L
            if (uin > 0) runCatching { openUserQzone(avatar, uin) }.onFailure { Utils.log("QzoneFeedCard openSpace: $it") }
        }
        avatar.isClickable = true; avatar.setOnClickListener(openSpace)
        val nameCol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; isClickable = true; setOnClickListener(openSpace) }
        nameCol.addView(TextView(ctx).apply {
            text = user?.nickName ?: ""
            setTextColor(M3.onSurface); textSize = 12.5f; typeface = Typeface.DEFAULT_BOLD
            isSingleLine = true
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        nameCol.addView(TextView(ctx).apply {
            text = runCatching { data.feedCommInfo?.displayTimeString }.getOrNull() ?: ""
            setTextColor(M3.onSurfaceTip); textSize = 10f; isSingleLine = true
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        header.addView(nameCol, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = 10.dp })
        card.addView(header, LinearLayout.LayoutParams(MATCH, WRAP))

        // --- body text (parsed: resolves [em] faces / @mentions) or mini-app card ---
        val bodyTv = TextView(ctx).apply {
            setTextColor(M3.onSurface); textSize = 13f
            setLineSpacing(2.dp.toFloat(), 1f)
        }
        // Main body: only render a mini-app card for a DIRECT share (no originalInfo fallback) — a
        // forwarded mini-app's card is rendered once by the quote section below, so falling back to
        // originalInfo here would render it twice.
        val isMiniApp = runCatching { QZoneMiniApp.bindText(bodyTv, data, allowOriginalFallback = false) }
            .getOrDefault(false)
        if (!isMiniApp) {
            val parsed = parsedBody(data, bodyTv)
            if (!parsed.isNullOrBlank()) {
                bodyTv.text = parsed
                card.addView(buildBody(ctx, bodyTv), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 8.dp })
            } else {
                dumpEmptyBody(data)
            }
        } else {
            card.addView(bodyTv, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 8.dp })
        }

        // --- forwarded original quote: "@原作者：内容" on one line, like the phone ---
        // Skip when the body already rendered a mini-app card: a direct 小程序 share carries the same
        // mini-app in both [data] and [data.originalInfo], so the quote would just duplicate the card.
        // A genuine forward-with-comment keeps its comment in [data] (isMiniApp false) and still quotes.
        runCatching {
            val orig = if (isMiniApp) null else data.originalInfo
            if (orig != null) {
                val quote = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = M3.rounded(M3.surfaceContainerHigh, M3.radiusMd)
                    setPadding(8.dp, 6.dp, 8.dp, 6.dp)
                }
                // Match the main body's size so EmoMatcher draws faces at the same scale (it sizes
                // sysface ImageSpans to the TextView's lineHeight).
                val origTv = TextView(ctx).apply {
                    setTextColor(M3.onSurface); textSize = 13f
                    setLineSpacing(2.dp.toFloat(), 1f)
                }
                val origIsMiniApp = runCatching { QZoneMiniApp.bindText(origTv, orig) }.getOrDefault(false)
                // The "@原作者：" prefix is folded into the single parsed SpannableString (see parsedBody)
                // so EmoMatcher's async face loader keeps updating the live text view.
                val body = if (origIsMiniApp) origTv.text
                           else parsedBody(orig, origTv, quoteAuthor = runCatching { orig.user }.getOrNull())
                if (!body.isNullOrBlank()) {
                    origTv.text = body
                    quote.addView(origTv, LinearLayout.LayoutParams(MATCH, WRAP))
                }
                if (quote.childCount > 0)
                    card.addView(quote, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 6.dp })
            }
        }.onFailure { Utils.log("QzoneFeedCard quote: $it") }

        // --- media grid ---
        val media = QzoneActions.mediaItems(data)
        if (media.isNotEmpty()) card.addView(buildMediaGrid(host, data, media), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 8.dp })

        // --- action row: like ........ ⋮ ---
        card.addView(buildActionRow(host, data), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 6.dp })

        // --- comments: preview when present, otherwise a "no comments yet" add button ---
        val comments = runCatching { data.cellCommentInfo?.c }.getOrNull().orEmpty()
        if (comments.isNotEmpty()) {
            card.addView(buildCommentPreview(host, data, comments), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 6.dp })
        } else {
            card.addView(TextView(ctx).apply {
                text = "还没有评论，点击添加评论"
                setTextColor(M3.onSurfaceTip); textSize = 12f
                background = M3.rounded(M3.surfaceContainerHigh, M3.radiusMd)
                setPadding(10.dp, 8.dp, 10.dp, 8.dp); isClickable = true
                setOnClickListener { QzoneActions.openComments(host, data) }
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 6.dp })
        }

        return wrapMargins(card)
    }

    /** The post body, optionally truncated to 5 lines with a 查看全文 expander ([Settings.qzoneTruncatePost]). */
    private fun buildBody(ctx: Context, tv: TextView): View {
        if (!Settings.qzoneTruncatePost.value) return tv
        tv.maxLines = 5
        tv.ellipsize = android.text.TextUtils.TruncateAt.END
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv, LinearLayout.LayoutParams(MATCH, WRAP))
        val more = TextView(ctx).apply {
            text = "查看全文"; setTextColor(M3.primary); textSize = 12.5f
            setPadding(0, 4.dp, 0, 0); visibility = View.GONE; isClickable = true
            setOnClickListener {
                tv.maxLines = Integer.MAX_VALUE
                tv.ellipsize = null
                visibility = View.GONE
            }
        }
        col.addView(more, LinearLayout.LayoutParams(WRAP, WRAP))
        // After layout, reveal the expander only if the text actually overflowed 5 lines.
        tv.post {
            runCatching {
                val l = tv.layout ?: return@post
                val overflow = l.lineCount > 5 || (0 until l.lineCount).any { l.getEllipsisCount(it) > 0 }
                if (overflow) more.visibility = View.VISIBLE
            }
        }
        return col
    }

    /**
     * Parsed summary (resolves QQ `[em]` faces + @mentions); falls back to raw text.
     *
     * When [quoteAuthor] is non-null (a forwarded/quoted block) a tappable, link-coloured
     * `@原作者：` prefix is folded into the SAME parsed [SpannableString]. This matters: EmoMatcher's
     * async face loader mutates that exact string and invalidates the view passed to [StringUtil.a],
     * so the prefix MUST live in the same object we set as `tv.text` — wrapping the result in a fresh
     * SpannableStringBuilder orphans it and freezes faces on the placeholder box.
     */
    private fun parsedBody(data: BusinessFeedData, tv: TextView, quoteAuthor: User? = null): CharSequence? {
        val cs = runCatching { data.cellSummaryV2 }.getOrNull() ?: return null
        val summary = cs.summary?.takeIf { it.isNotEmpty() } ?: return null
        Utils.log("QzoneBody raw=$summary quote=${quoteAuthor != null}")
        // getParsedSummary(nick, view) PREPENDS nick to the body and CACHES the result against that
        // view (stale after our card rebuilds → emoji placeholder). Parse fresh via StringUtil.a against
        // the live view.
        val nick = runCatching { data.user?.nickName }.getOrNull()
        // Quote mode: "@原作者" + separator. The forwarded original's summary already starts with "：",
        // so only inject a separator when it doesn't. The native niche prefix (bare nick) applies only
        // outside quote mode.
        val quoteNick = quoteAuthor?.nickName
        val atRun = if (quoteAuthor != null && !quoteNick.isNullOrBlank()) "@$quoteNick" else ""
        val prefix = when {
            atRun.isNotEmpty() -> atRun + (if (summary.startsWith("：") || summary.startsWith(":")) "" else "：")
            !nick.isNullOrEmpty() && !summary.startsWith(":") && summary.startsWith("：") -> nick
            else -> ""
        }
        // Swap Unicode-emoji [em] codes for their chars first, then let StringUtil.a do @mentions +
        // classic image sysfaces. The prefix carries no [em], so substitution never shifts offset 0.
        val full = QzoneEmoji.substitute(prefix + summary)
        val rendered = runCatching { StringUtil.a(full, tv) }.getOrNull() ?: SpannableString(full)
        // Re-attach the uin StringUtil.a discards, so @mentions open the mentioned user's QZone.
        val linked = runCatching { QzoneMentions.linkify(full, rendered, tv) }.getOrNull() ?: rendered
        // Colour + link the "@原作者" run (separator excluded) on the SAME spannable.
        if (atRun.isNotEmpty() && linked is Spannable) runCatching {
            val uin = quoteAuthor?.uin ?: 0L
            if (uin > 0L) {
                linked.setSpan(object : ClickableSpan() {
                    override fun onClick(w: View) {
                        runCatching { openUserQzone(w, uin) }.onFailure { Utils.log("QzoneFeedCard quote @: $it") }
                    }
                    override fun updateDrawState(ds: TextPaint) { ds.color = linkColorResolved(); ds.isUnderlineText = false }
                }, 0, atRun.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                tv.movementMethod = LinkMovementMethod.getInstance()
            } else {
                linked.setSpan(ForegroundColorSpan(linkColorResolved()), 0, atRun.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        return linked
    }

    /** Reflectively read CellTitleInfo.title/displayTitle (field name unknown at compile time). */
    private fun titleOf(d: BusinessFeedData?): String? = runCatching {
        val cti = d?.javaClass?.getField("cellTitleInfo")?.get(d) ?: return null
        val t = runCatching { cti.javaClass.getField("title").get(cti) as? String }.getOrNull()
        val dt = runCatching { cti.javaClass.getField("displayTitle").get(cti) as? String }.getOrNull()
        "title='$t' disp='$dt'"
    }.getOrNull()

    /**
     * A feed rendered with NO body text. Dump every candidate text field so we can find where the
     * content actually lives for this post type (e.g. cellTitleInfo vs cellSummaryV2, or an unloaded
     * originalInfo) and add the right fallback in parsedBody.
     */
    private fun dumpEmptyBody(data: BusinessFeedData) {
        runCatching {
            val fk = runCatching { data.feedCommInfo?.feedskey }.getOrNull()
            val sumV2 = runCatching { data.cellSummaryV2?.summary }.getOrNull()
            val orig = runCatching { data.originalInfo }.getOrNull()
            val origSum = runCatching { orig?.cellSummaryV2?.summary }.getOrNull()
            Utils.log("QzoneEmptyBody fk=$fk sumV2='$sumV2' ${titleOf(data)} orig=${orig != null} origSum='$origSum' origTitle=${titleOf(orig)}")
            // Genuinely empty (no summary OR original summary) — enumerate EVERY non-empty String we can
            // reach so we find where this post type actually stores its text.
            if (sumV2.isNullOrEmpty() && origSum.isNullOrEmpty()) {
                Utils.log("QzoneEmptyBody.deep[data]: ${probeStrings(data)}")
                if (orig != null) Utils.log("QzoneEmptyBody.deep[orig]: ${probeStrings(orig)}")
            }
        }
    }

    /** Reflectively collect "field=value" for every non-empty String on [obj] and one level into its
     *  `cell*` sub-objects, so a hidden text field surfaces in the log. */
    private fun probeStrings(obj: Any?): String {
        obj ?: return "null"
        val out = StringBuilder()
        runCatching {
            for (f in obj.javaClass.fields + obj.javaClass.declaredFields) {
                runCatching {
                    f.isAccessible = true
                    val v = f.get(obj) ?: return@runCatching
                    when {
                        v is String && v.isNotBlank() -> out.append("${f.name}='${v.take(50)}' ")
                        v is CharSequence && v.isNotBlank() -> out.append("${f.name}~='${v.toString().take(50)}' ")
                        f.name.startsWith("cell") -> {
                            // one level: pull a 'summary'/'title'/'content' string off the sub-object
                            for (sub in arrayOf("summary", "title", "displayTitle", "content", "text")) {
                                runCatching {
                                    val sv = v.javaClass.getField(sub).get(v) as? String
                                    if (!sv.isNullOrBlank()) out.append("${f.name}.$sub='${sv.take(50)}' ")
                                }
                            }
                        }
                    }
                }
            }
        }
        return out.toString().ifEmpty { "(no non-empty strings)" }
    }

    /** Dump candidate forward/repost fields so we can discover whether reposter data is available. */
    private fun dumpRepostDebug(data: BusinessFeedData) {
        runCatching {
            val fc = data.feedCommInfo
            val busi = runCatching { data.operationInfo?.busiParam }.getOrNull()
            Utils.log("QzoneRepost: feedskey=${fc?.feedskey} isForward=${data.isForwardFeedData} owner=${data.owner_uin} busiParam=$busi")
        }
    }

    private fun buildActionRow(host: IAdapterHost, data: BusinessFeedData): View {
        val ctx = host.requireContext()
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val liked = runCatching { data.likeInfo?.isLiked == true }.getOrDefault(false)
        val likeNum = runCatching { data.likeInfo?.likeNum ?: 0 }.getOrDefault(0)

        val likeTv = TextView(ctx).apply {
            text = if (likeNum > 0) likeNum.toString() else "赞"
            setTextColor(if (liked) M3.primary else M3.onSurfaceVariant); textSize = 12f
            leadingSymbol(MaterialSymbols.thumb_up, if (liked) M3.primary else M3.onSurfaceVariant, 17)
            setPadding(2.dp, 8.dp, 16.dp, 8.dp); isClickable = true
        }
        likeTv.setOnClickListener {
            val nowLiked = QzoneActions.toggleLike(host, data)
            val n = runCatching { data.likeInfo?.likeNum ?: 0 }.getOrDefault(0)
            likeTv.text = if (n > 0) n.toString() else "赞"
            likeTv.setTextColor(if (nowLiked) M3.primary else M3.onSurfaceVariant)
            likeTv.leadingSymbol(MaterialSymbols.thumb_up, if (nowLiked) M3.primary else M3.onSurfaceVariant, 17)
        }
        row.addView(likeTv, LinearLayout.LayoutParams(WRAP, WRAP))
        // spacer pushes the overflow icon to the right of the same row as 赞
        row.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        val overflow = symbolImage(ctx, MaterialSymbols.more_vert, M3.onSurfaceVariant, sizeDp = 20).apply {
            setPadding(12.dp, 4.dp, 4.dp, 4.dp)
            isClickable = true
            setOnClickListener { QzoneActions.showOverflowMenu(host, data) }
        }
        row.addView(overflow, LinearLayout.LayoutParams(WRAP, WRAP))
        return row
    }

    /** Comment preview truncated by total text length (one very long comment is enough), not a fixed count. */
    private fun buildCommentPreview(host: IAdapterHost, data: BusinessFeedData, comments: List<Comment>): View {
        val ctx = host.requireContext()
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = M3.rounded(M3.surfaceContainerHigh, M3.radiusMd)
            setPadding(10.dp, 8.dp, 10.dp, 8.dp)
        }
        var chars = 0
        var shown = 0
        for (c in comments) {
            if (shown > 0 && (chars > 60 || shown >= 3)) break
            // Cap each preview comment to 4 lines so one very long comment can't fill the card.
            box.addView(commentRow(ctx, c.user, c.user?.nickName, null, c.comment, small = false, maxLines = 4))
            chars += (c.comment?.length ?: 0)
            shown++
        }
        // Count comments AND all their replies (incl. reply-of-reply): comment_num (field b) counts
        // only top-level comments, so add each comment's server reply count (replyNum; replies are
        // stored flat per comment, so this already includes replies to replies).
        val total = runCatching {
            val base = data.cellCommentInfo?.b?.takeIf { it > 0 } ?: comments.size
            base + comments.sumOf { maxOf(it.replyNum, it.replies?.size ?: 0) }
        }.getOrDefault(comments.size)
        box.addView(TextView(ctx).apply {
            text = "查看全部 $total 条评论"
            setTextColor(M3.primary); textSize = 12f
            setPadding(0, 8.dp, 0, 2.dp); isClickable = true
            setOnClickListener { QzoneActions.openComments(host, data) }
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        return box
    }

    /**
     * One structured comment/reply row (M3 list-item style): leading circular avatar + an author
     * headline; replies carry a compact "回复 〈target〉" overline on its own line; the body text
     * wraps full-width below (never inline-prefixed, so it can't turn into a wrapped mess).
     */
    fun commentRow(ctx: Context, author: User?, authorNick: String?, replyTarget: String?, content: String?, small: Boolean, maxLines: Int = 0): View {
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 5.dp, 0, 5.dp)
        }
        val av = circleAvatar(ctx, if (small) 22 else 26)
        row.addView(av, LinearLayout.LayoutParams((if (small) 22 else 26).dp, (if (small) 22 else 26).dp).apply { topMargin = 1.dp })
        runCatching {
            val uin = author?.uin ?: 0L
            av.loadPicUrl(author?.avatarPath?.takeIf { it.isNotEmpty() } ?: QzoneActions.avatarUrl(uin), "qzcm_$uin")
        }
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (replyTarget != null) col.addView(TextView(ctx).apply {
            text = "回复 $replyTarget"
            setTextColor(M3.onSurfaceTip); textSize = 10f; isSingleLine = true
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(TextView(ctx).apply {
            text = authorNick ?: ""
            setTextColor(M3.onSurfaceVariant); textSize = 11f; typeface = Typeface.DEFAULT_BOLD; isSingleLine = true
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        col.addView(TextView(ctx).apply {
            val withEmoji = QzoneEmoji.substitute(content ?: "")
            setText(runCatching { StringUtil.a(withEmoji, this) }.getOrNull() ?: withEmoji)
            setTextColor(M3.onSurface); textSize = 12f
            if (maxLines > 0) { this.maxLines = maxLines; ellipsize = android.text.TextUtils.TruncateAt.END }
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = 1.dp })
        row.addView(col, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = 8.dp })
        return row
    }

    private fun buildMediaGrid(host: IAdapterHost, data: BusinessFeedData, media: List<momoi.mod.qqpro.hook.view.MediaItem>): View {
        val ctx = host.requireContext()
        val videoUrl = if (media.size == 1) media[0].videoUrl else null
        // Single VIDEO → cover thumbnail + inline player (tap to play in place), 16:9 box.
        if (videoUrl != null) {
            val dm = ctx.resources.displayMetrics
            val h = ((dm.widthPixels - 36.dp) * 9 / 16).coerceIn(120.dp, 280.dp)
            val frame = FrameLayout(ctx).apply {
                setClipToOutlineCompat(true)
                background = M3.rounded(M3.surfaceContainerHigh, M3.radiusMd)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) outlineProvider = roundOutline(M3.radiusMd)
            }
            val vi = runCatching { (data.originalInfo ?: data).videoInfo }.getOrNull()
            val cover = runCatching { (vi?.coverUrl ?: vi?.currentUrl ?: vi?.bigUrl ?: vi?.originUrl)?.url }.getOrNull()
            Utils.log("QzoneVideo: url=$videoUrl cover=$cover")
            // Cover image at rest (InlineVideoView is transparent until it plays).
            val coverIv = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; maxHeight = h }
            runCatching { coverIv.loadPicUrl(cover, "qzv_${cover.hashCode()}") }
            frame.addView(coverIv, FrameLayout.LayoutParams(MATCH, h))
            frame.addView(symbolImage(ctx, MaterialSymbols.play_arrow, android.graphics.Color.WHITE, 48),
                FrameLayout.LayoutParams(48.dp, 48.dp, Gravity.CENTER))
            // Honour 单视频帖子内联播放: on → inline player (tap to play in place); off → tapping the
            // cover opens the fullscreen viewer (mirrors the native QZoneInlineVideo gate).
            if (Settings.qzoneInlineVideo.value) {
                // Give the player a fixed height via the child (the caller sets the frame to WRAP).
                frame.addView(InlineVideoView(ctx, videoUrl), FrameLayout.LayoutParams(MATCH, h))
            } else {
                frame.isClickable = true
                frame.setOnClickListener { QzoneActions.openMedia(host, data, 0) }
            }
            return frame
        }
        // Single IMAGE → one thumbnail cropped to between 4:3 and 16:9.
        if (media.size == 1) {
            val frame = FrameLayout(ctx)
            val iv = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                maxHeight = 400.dp
                setClipToOutlineCompat(true)
                background = M3.rounded(M3.surfaceContainerHigh, M3.radiusMd)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) outlineProvider = roundOutline(M3.radiusMd)
            }
            val cover = media[0].imageUrl
            frame.addView(iv, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.CENTER))
            // Provisional height while loading: the ImageView is WRAP and has no drawable yet, so without
            // this the cell collapses to 0px and the spinner (match-parent of a 0-height frame) is
            // invisible. Cleared on completion; clampSingleAspect then sets the real aspect height.
            if (!cover.isNullOrEmpty()) {
                frame.minimumHeight = 160.dp
                // Show a centered spinner while the (possibly large/GIF) image downloads.
                M3Progress.show(frame, 28, color = M3.primary)
            }
            runCatching {
                iv.loadPicUrl(cover, "qzm_${cover.hashCode()}", onDone = { ok ->
                    M3Progress.hide(frame)
                    frame.minimumHeight = 0
                    if (ok) iv.post { clampSingleAspect(iv) }
                })
            }
            frame.isClickable = true
            frame.setOnClickListener { QzoneActions.openMedia(host, data, 0) }
            return frame
        }

        // Truncate mode: only two square thumbnails (2nd darkened with +N).
        if (Settings.qzoneTruncateImages.value) {
            val dm = ctx.resources.displayMetrics
            val square = ((dm.widthPixels - 36.dp - 6.dp) / 2).coerceAtLeast(60.dp)
            val rowLl = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            for (idx in 0 until minOf(2, media.size)) {
                val cell = squareCell(ctx, media[idx].imageUrl, M3.radiusMd)
                if (idx == 1 && media.size > 2) cell.addView(TextView(ctx).apply {
                    text = "+${media.size - 2}"
                    setTextColor(android.graphics.Color.WHITE); textSize = 18f; gravity = Gravity.CENTER
                    setBackgroundColor(0x80_000000.toInt())
                }, FrameLayout.LayoutParams(MATCH, MATCH))
                cell.isClickable = true; cell.setOnClickListener { QzoneActions.openMedia(host, data, idx) }
                rowLl.addView(cell, LinearLayout.LayoutParams(square, square).apply { marginEnd = if (idx == 0) 6.dp else 0 })
            }
            return rowLl
        }

        // Full mode: 3-column grid of square thumbnails (cap 9, +N overlay on the last).
        val grid = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val show = minOf(media.size, 9)
        var i = 0
        while (i < show) {
            val rowLl = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
            var col = 0
            while (col < 3 && i < show) {
                val idx = i
                val cell = squareCell(ctx, media[idx].imageUrl, M3.radiusSm)
                if (idx == show - 1 && media.size > show) cell.addView(TextView(ctx).apply {
                    text = "+${media.size - show}"
                    setTextColor(android.graphics.Color.WHITE); textSize = 16f; gravity = Gravity.CENTER
                    setBackgroundColor(0x66_000000.toInt())
                }, FrameLayout.LayoutParams(MATCH, MATCH))
                cell.isClickable = true
                cell.setOnClickListener { QzoneActions.openMedia(host, data, idx) }
                rowLl.addView(cell, LinearLayout.LayoutParams(0, 96.dp, 1f).apply {
                    marginEnd = if (col < 2) 4.dp else 0; bottomMargin = 4.dp
                })
                col++; i++
            }
            while (col < 3) { rowLl.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f)); col++ }
            grid.addView(rowLl, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        return grid
    }

    private fun squareCell(ctx: Context, url: String?, radius: Float): FrameLayout {
        // Clip the CELL (not just the image) so the +N overlay also respects the rounded corners.
        val cell = FrameLayout(ctx).apply {
            setClipToOutlineCompat(true)
            background = M3.rounded(M3.surfaceContainerHigh, radius)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) outlineProvider = roundOutline(radius)
        }
        val iv = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            maxHeight = 400.dp
        }
        cell.addView(iv, FrameLayout.LayoutParams(MATCH, MATCH))
        // Spinner while the thumbnail downloads (hidden on completion), so a slow/large image isn't a
        // blank box with no feedback.
        if (!url.isNullOrEmpty()) M3Progress.show(cell, 20, color = M3.primary)
        runCatching { iv.loadPicUrl(url, "qzm_${url.hashCode()}", onDone = { M3Progress.hide(cell) }) }
        return cell
    }

    /** Clamp a single image/video thumbnail to a height between 4:3 and 16:9 of its current width. */
    private fun clampSingleAspect(iv: ImageView) {
        val w = iv.width.takeIf { it > 0 } ?: return
        val d = iv.drawable ?: return
        val iw = d.intrinsicWidth.takeIf { it > 0 } ?: return
        val ih = d.intrinsicHeight.takeIf { it > 0 } ?: return
        val ratio = (iw.toFloat() / ih.toFloat()).coerceIn(4f / 3f, 16f / 9f) // w/h within [1.33, 1.78]
        val h = (w / ratio).toInt()
        if (iv.layoutParams.height != h) { iv.layoutParams.height = h; iv.requestLayout() }
    }

    private fun roundOutline(radius: Float): ViewOutlineProvider? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) =
                outline.setRoundRect(0, 0, view.width, view.height, radius)
        } else null

    /** Wrap a card body so it gets uniform list margins. */
    private fun wrapMargins(card: View): View {
        val ctx = card.context
        return FrameLayout(ctx).apply {
            setPadding(6.dp, 4.dp, 6.dp, 4.dp)
            addView(card, FrameLayout.LayoutParams(MATCH, WRAP))
        }
    }

    private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
}
