package momoi.mod.qqpro.hook.view

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import momoi.mod.qqpro.api.GroupBulletinApi
import momoi.mod.qqpro.api.WebQunApi
import momoi.mod.qqpro.lib.FILL
import momoi.mod.qqpro.lib.SwipeBackLayout
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.vertical
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3Progress
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.lib.material.leadingSymbol
import momoi.mod.qqpro.util.Utils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val BG = M3.surface
private val ACCENT get() = M3.primary
private val timeFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())

/**
 * Full-screen viewer for a group's active announcements (群公告). Opened from the group info
 * page ([com.tencent.watch.aio_impl.ui.frames.SettingFrame]) entry added in [GroupBulletin].
 * 优先走网页接口（[WebQunApi]）：用本机票据拉完整公告历史 + 群精华消息（机器人 markdown
 * 公告/精华也能显示原文）；网页接口不可用时回退原生 [GroupBulletinApi]（生效中公告）。
 */
class GroupBulletinFragment(private val groupCode: Long) : MyDialogFragment() {

    private lateinit var root: LinearLayout
    private var active = true

    override fun onDestroyView() {
        active = false
        super.onDestroyView()
    }

    override fun onStart() {
        super.onStart()
        // Force the dialog window to fill the screen so the SwipeBackLayout covers it entirely.
        // Without this the window wraps its content height; when there are no announcements the
        // centered "暂无群公告" TextView is the only content, the window collapses to it, and a
        // swipe-back anywhere outside that small text is dead — trapping the user on the screen.
        dialog?.window?.setLayout(
            android.view.WindowManager.LayoutParams.MATCH_PARENT,
            android.view.WindowManager.LayoutParams.MATCH_PARENT
        )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val swipe = SwipeBackLayout(inflater.context)
        swipe.layoutParams = ViewGroup.LayoutParams(FILL, FILL)
        swipe.setBackgroundColor(BG)
        swipe.onSwipeBack = { dismiss() }
        root = LinearLayout(inflater.context).vertical()
        root.setBackgroundColor(BG)
        swipe.addView(root, FrameLayout.LayoutParams(FILL, FILL))

        // M3 loading spinner while the bulletin list is fetched; the callback replaces the content
        // (showList/showCentered call removeAllViews) which clears the overlay.
        M3Progress.show(root, sizeDp = 36)
        loadContent()
        return swipe
    }

    /**
     * 并行拉网页公告 + 群精华；两者都到齐后渲染。网页公告失败（null）时回退原生公告接口，
     * 精华区照常显示（精华失败则只显示原生公告）。
     */
    private fun loadContent() {
        var announce: List<WebQunApi.AnnounceItem>? = null
        var essence: List<WebQunApi.EssenceItem>? = null
        var pending = 2
        val latch = {
            pending -= 1
            if (pending == 0) finalize(announce, essence)
        }
        WebQunApi.fetchAnnouncements(groupCode) { a ->
            announce = a
            latch()
        }
        WebQunApi.fetchEssence(groupCode) { e ->
            essence = e
            latch()
        }
    }

    private fun finalize(
        announce: List<WebQunApi.AnnounceItem>?,
        essence: List<WebQunApi.EssenceItem>?,
    ) {
        if (!active) return
        if (announce != null) {
            showWeb(announce, essence.orEmpty())
            return
        }
        // 网页接口拿不到（无票据/网络/被拒）→ 原生公告：先完整历史，再回退“生效中”。
        GroupBulletinApi.fetchFull(groupCode) { items ->
            if (!active) return@fetchFull
            if (items.isEmpty()) {
                GroupBulletinApi.fetch(groupCode) { activeItems ->
                    if (!active) return@fetch
                    showNative(activeItems, essence.orEmpty())
                }
            } else {
                showNative(items, essence.orEmpty())
            }
        }
    }

    private fun showCentered(msg: String) {
        root.removeAllViews()
        root.gravity = Gravity.CENTER
        val tv = TextView(requireContext()).apply {
            text = msg
            textSize = 14f
            setTextColor(M3.onSurfaceVariant)
            gravity = Gravity.CENTER
        }
        root.addView(tv, LinearLayout.LayoutParams(FILL, ViewGroup.LayoutParams.WRAP_CONTENT))
    }

    /** 网页接口渲染：完整公告历史 + 群精华。 */
    private fun showWeb(
        items: List<WebQunApi.AnnounceItem>,
        essence: List<WebQunApi.EssenceItem>,
    ) {
        root.removeAllViews()
        root.gravity = Gravity.NO_GRAVITY
        val ctx = requireContext()
        val sv = ScrollView(ctx)
        sv.isFillViewport = true
        sv.layoutParams = LinearLayout.LayoutParams(FILL, 0, 1f)
        val col = LinearLayout(ctx).vertical()
        col.setPadding(16.dp, 8.dp, 16.dp, 16.dp)
        col.layoutParams = ViewGroup.LayoutParams(FILL, ViewGroup.LayoutParams.WRAP_CONTENT)
        sv.addView(col)
        root.addView(sv)

        // Title
        col.addView(TextView(ctx).apply {
            text = "群公告"
            textSize = 15f
            setTextColor(M3.onSurface)
            gravity = Gravity.CENTER
            setPadding(0, 14.dp, 0, 12.dp)
        }, LinearLayout.LayoutParams(FILL, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (items.isEmpty()) {
            col.addView(hintView("暂无群公告"))
        } else {
            items.forEach { col.addView(webCard(it)) }
        }
        appendEssence(col, essence)
    }

    /** 原生内核渲染（网页接口失败的回退路径）：公告 + 精华。 */
    private fun showNative(
        items: List<GroupBulletinApi.Item>,
        essence: List<WebQunApi.EssenceItem>,
    ) {
        root.removeAllViews()
        root.gravity = Gravity.NO_GRAVITY
        val ctx = requireContext()
        val sv = ScrollView(ctx)
        sv.isFillViewport = true
        sv.layoutParams = LinearLayout.LayoutParams(FILL, 0, 1f)
        val col = LinearLayout(ctx).vertical()
        col.setPadding(16.dp, 8.dp, 16.dp, 16.dp)
        col.layoutParams = ViewGroup.LayoutParams(FILL, ViewGroup.LayoutParams.WRAP_CONTENT)
        sv.addView(col)
        root.addView(sv)

        col.addView(TextView(ctx).apply {
            text = "群公告"
            textSize = 15f
            setTextColor(M3.onSurface)
            gravity = Gravity.CENTER
            setPadding(0, 14.dp, 0, 12.dp)
        }, LinearLayout.LayoutParams(FILL, ViewGroup.LayoutParams.WRAP_CONTENT))

        if (items.isEmpty()) {
            col.addView(hintView("暂无群公告"))
        } else {
            // Pinned announcements first, then newest first.
            items.sortedWith(compareByDescending<GroupBulletinApi.Item> { it.pinned }.thenByDescending { it.time })
                .forEach { col.addView(card(it)) }
        }
        appendEssence(col, essence)
    }

    private fun hintView(msg: String): TextView = TextView(requireContext()).apply {
        text = msg
        textSize = 13f
        setTextColor(M3.onSurfaceVariant)
        gravity = Gravity.CENTER
        setPadding(0, 10.dp, 0, 14.dp)
    }

    /** 群精华区块：标题 + 卡片列表（空则提示）。 */
    private fun appendEssence(col: LinearLayout, essence: List<WebQunApi.EssenceItem>) {
        val ctx = requireContext()
        col.addView(TextView(ctx).apply {
            text = "群精华"
            textSize = 15f
            setTextColor(M3.onSurface)
            gravity = Gravity.CENTER
            setPadding(0, 18.dp, 0, 8.dp)
        }, LinearLayout.LayoutParams(FILL, ViewGroup.LayoutParams.WRAP_CONTENT))
        if (essence.isEmpty()) {
            col.addView(hintView("暂无精华消息"))
            return
        }
        essence.forEach { col.addView(essenceCard(it)) }
    }

    private fun card(item: GroupBulletinApi.Item): View {
        val ctx = requireContext()
        val card = LinearLayout(ctx).vertical()
        card.setPadding(14.dp, 12.dp, 14.dp, 12.dp)
        card.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(M3.surfaceContainer)
            cornerRadius = 14.dp.toFloat()
        }
        val lp = LinearLayout.LayoutParams(FILL, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = 8.dp
        card.layoutParams = lp

        // header: pin tag + time
        val timeStr = if (item.time > 0) timeFmt.format(Date(item.time.toLong() * 1000L)) else ""
        if (item.pinned || timeStr.isNotEmpty()) {
            card.addView(TextView(ctx).apply {
                text = timeStr
                textSize = 11f
                setTextColor(ACCENT)
                setPadding(0, 0, 0, 6.dp)
                if (item.pinned) leadingSymbol(MaterialSymbols.push_pin, ACCENT, sizeDp = 12, gap = 4)
            })
        }

        val body = item.text.ifBlank { "(无文字内容)" }
        card.addView(TextView(ctx).apply {
            // 公告正文是 Markdown（桌面版实测支持 **加粗**、链接、列表等），用轻量渲染器转成
            // Spannable 显示；纯文本公告原样显示。
            text = momoi.mod.qqpro.lib.Markdown.toSpannable(body)
            textSize = 14f
            setTextColor(M3.onSurface)
            setTextIsSelectable(true)
        })

        item.images.forEachIndexed { idx, image ->
            val label = if (item.images.size == 1) "查看图片" else "查看图片 ${idx + 1}"
            card.addView(TextView(ctx).apply {
                text = label
                textSize = 13f
                setTextColor(ACCENT)
                setPadding(0, 8.dp, 0, 0)
                leadingSymbol(MaterialSymbols.image, ACCENT, sizeDp = 14, gap = 4)
                setOnClickListener { openImage(this, image) }
            })
        }
        return card
    }

    /** 网页公告卡片：时间 + Markdown 正文 + 图片入口。 */
    private fun webCard(item: WebQunApi.AnnounceItem): View {
        val ctx = requireContext()
        val card = LinearLayout(ctx).vertical()
        card.setPadding(14.dp, 12.dp, 14.dp, 12.dp)
        card.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(M3.surfaceContainer)
            cornerRadius = 14.dp.toFloat()
        }
        val lp = LinearLayout.LayoutParams(FILL, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = 8.dp
        card.layoutParams = lp

        if (item.time > 0) {
            card.addView(TextView(ctx).apply {
                text = timeFmt.format(Date(item.time * 1000L))
                textSize = 11f
                setTextColor(ACCENT)
                setPadding(0, 0, 0, 6.dp)
            })
        }

        val body = item.text.ifBlank { "(无文字内容)" }
        card.addView(TextView(ctx).apply {
            text = momoi.mod.qqpro.lib.Markdown.toSpannable(body)
            textSize = 14f
            setTextColor(M3.onSurface)
            setTextIsSelectable(true)
        })

        item.imageIds.forEachIndexed { idx, id ->
            val label = if (item.imageIds.size == 1) "查看图片" else "查看图片 ${idx + 1}"
            card.addView(TextView(ctx).apply {
                text = label
                textSize = 13f
                setTextColor(ACCENT)
                setPadding(0, 8.dp, 0, 0)
                leadingSymbol(MaterialSymbols.image, ACCENT, sizeDp = 14, gap = 4)
                setOnClickListener { openAnnounceImage(this, id) }
            })
        }
        return card
    }

    /** 精华消息卡片：发送者 + 时间 + 正文（Markdown）+ 图片入口。 */
    private fun essenceCard(item: WebQunApi.EssenceItem): View {
        val ctx = requireContext()
        val card = LinearLayout(ctx).vertical()
        card.setPadding(14.dp, 12.dp, 14.dp, 12.dp)
        card.background = android.graphics.drawable.GradientDrawable().apply {
            setColor(M3.surfaceContainer)
            cornerRadius = 14.dp.toFloat()
        }
        val lp = LinearLayout.LayoutParams(FILL, ViewGroup.LayoutParams.WRAP_CONTENT)
        lp.topMargin = 8.dp
        card.layoutParams = lp

        val header = buildString {
            append(item.senderNick)
            if (item.time > 0) append(" · ").append(timeFmt.format(Date(item.time * 1000L)))
        }
        card.addView(TextView(ctx).apply {
            text = header
            textSize = 11f
            setTextColor(ACCENT)
            setPadding(0, 0, 0, 6.dp)
        })

        val body = item.text.ifBlank { if (item.imageUrl != null) "(图片消息)" else "(无文字内容)" }
        card.addView(TextView(ctx).apply {
            text = momoi.mod.qqpro.lib.Markdown.toSpannable(body)
            textSize = 14f
            setTextColor(M3.onSurface)
            setTextIsSelectable(true)
        })

        item.imageUrl?.let { url ->
            card.addView(TextView(ctx).apply {
                text = "查看图片"
                textSize = 13f
                setTextColor(ACCENT)
                setPadding(0, 8.dp, 0, 0)
                leadingSymbol(MaterialSymbols.image, ACCENT, sizeDp = 14, gap = 4)
                setOnClickListener { openWebImage(this, url) }
            })
        }
        return card
    }

    /** 网页公告图片：id 是 gdynamic 直链 id，构造 URL 复用 [GroupBulletinApi.downloadImage]。 */
    private fun openAnnounceImage(view: TextView, id: String) {
        openWebImage(view, "http://gdynamic.qpic.cn/gdynamic/$id")
    }

    private fun openWebImage(view: TextView, url: String) {
        val original = view.text
        view.text = "下载中…"
        view.isEnabled = false
        GroupBulletinApi.downloadImage(GroupBulletinApi.Image("", "", url)) { bmp ->
            if (!active) return@downloadImage
            view.text = original
            view.isEnabled = true
            if (bmp == null) {
                Utils.toast(requireContext(), "图片下载失败")
                return@downloadImage
            }
            runCatching {
                momoi.mod.qqpro.hook.aio_cell.BigImageFragment(bmp = bmp)
                    .show(childFragmentManager, "bulletin_image")
            }.onFailure { Utils.log("GroupBulletin: show image failed: $it") }
        }
    }

    private fun openImage(view: TextView, image: GroupBulletinApi.Image) {
        val original = view.text
        view.text = "下载中…"
        view.isEnabled = false
        GroupBulletinApi.downloadImage(image) { bmp ->
            if (!active) return@downloadImage
            view.text = original
            view.isEnabled = true
            if (bmp == null) {
                Utils.toast(requireContext(), "图片下载失败")
                return@downloadImage
            }
            runCatching {
                momoi.mod.qqpro.hook.aio_cell.BigImageFragment(bmp = bmp)
                    .show(childFragmentManager, "bulletin_image")
            }.onFailure { Utils.log("GroupBulletin: show image failed: $it") }
        }
    }
}
