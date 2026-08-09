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
 * Fetches via [GroupBulletinApi.fetch] (kernel getGroupBulletin) and lists each announcement's
 * text. See [GroupBulletinApi] for why only active announcements are available on the watch.
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
        GroupBulletinApi.fetch(groupCode) { items ->
            if (!active) return@fetch
            if (items.isEmpty()) showCentered("暂无群公告")
            else showList(items)
        }
        return swipe
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

    private fun showList(items: List<GroupBulletinApi.Item>) {
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

        // Pinned announcements first, then newest first.
        items.sortedWith(compareByDescending<GroupBulletinApi.Item> { it.pinned }.thenByDescending { it.time })
            .forEach { col.addView(card(it)) }
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
            text = body
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
