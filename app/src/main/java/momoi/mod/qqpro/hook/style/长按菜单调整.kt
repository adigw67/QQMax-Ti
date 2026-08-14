package momoi.mod.qqpro.hook.style
import momoi.mod.qqpro.lib.setClipToOutlineCompat

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import com.tencent.watch.aio_impl.data.WatchAIOMsgItem
import com.tencent.watch.aio_impl.ui.cell.base.WatchAIOGroupWidgetItemCell
import com.tencent.watch.aio_impl.ui.menu.AIOLongClickMenuFragment
import com.tencent.watch.aio_impl.ui.menu.MenuItemFactory
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.hook.ChatMultiSelect
import momoi.mod.qqpro.hook.HistoryMsgRegistry
import momoi.mod.qqpro.hook.action.CurrentMsgList
import momoi.mod.qqpro.hook.menu.Entry
import momoi.mod.qqpro.hook.menu.buildMessageActions
import momoi.mod.qqpro.hook.menu.menuScope
import momoi.mod.qqpro.lib.SwipeBackLayout
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.util.Utils
import kotlinx.coroutines.launch

/**
 * 长按菜单 Material 重建 — DISPLAY ONLY. The action set itself (and recall eligibility) lives in the
 * shared [momoi.mod.qqpro.hook.menu] data layer ([buildMessageActions]) so the long-press menu and
 * 消息多选 stay in lockstep. This file just builds the view tree (rounded M3 card / scrim / swipe-back)
 * and renders the awaited [Entry] list as Material rows.
 *
 * Inside a 合并转发聊天记录 history viewer the message isn't live, so only read-only entries are shown.
 */
@Mixin
class 长按菜单调整(p0: (MenuItemFactory.ItemEnum) -> Unit, p1: String?) :
    AIOLongClickMenuFragment(p0, p1) {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val msgId = arguments?.getLong("key_msg_id") ?: 0L
        val cellItem = runCatching {
            val field = this.b.javaClass.getDeclaredField("b")
            field.isAccessible = true
            (field.get(this.b) as WatchAIOGroupWidgetItemCell<*, *>).f()
        }.getOrNull()
        val liveItem = cellItem ?: CurrentMsgList.msgList.value.find { it.d.msgId == msgId }
        val isHistory = liveItem == null
        val msg = liveItem?.d ?: HistoryMsgRegistry.find(msgId)
        val fm = runCatching { parentFragmentManager }.getOrNull()
        Utils.log("menu: msg=${msg != null} history=$isHistory msgId=$msgId")
        // 消息多选: a long-press while multi-select is already active RANGE-selects from the last
        // selection to the pressed message and dismisses without showing the menu (a plain tap, handled
        // on the gesture path, still toggles a single message). One range path.
        if (ChatMultiSelect.active) {
            ChatMultiSelect.selectRangeTo(msg?.msgId ?: msgId)
            runCatching { dismiss() }
            return View(inflater.context)
        }
        return LongPressMenu.build(this, inflater, msg, liveItem, fm, isHistory)
    }
}

object LongPressMenu {

    @SuppressLint("ClickableViewAccessibility")
    fun build(
        fragment: AIOLongClickMenuFragment,
        inflater: LayoutInflater,
        msg: MsgRecord?,
        msgItem: WatchAIOMsgItem?,
        fm: androidx.fragment.app.FragmentManager?,
        isHistory: Boolean,
    ): View {
        val ctx = inflater.context
        // Material = one M3 surface card with ripple rows; non-material = semi-transparent dark rows.
        val material = Settings.materialLongPressMenu.value
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
        }

        // The action set comes from the shared data layer. names = the kernel's item list (enables the
        // native entries); native = the fragment-backed dispatcher. Built async (recall awaits a role
        // lookup), then the rows are populated once — guarded against a dismissed/detached fragment.
        val names = runCatching { fragment.requireArguments().getStringArrayList("key_item_list") }
            .getOrNull().orEmpty().toSet()
        val native: (String) -> Unit = { n ->
            runCatching { fragment.b.invoke(MenuItemFactory.ItemEnum.valueOf(n)) }
                .onFailure { Utils.log("menu native $n: $it") }
        }
        menuScope.launch {
            val actions = buildMessageActions(card, msg, msgItem, fm, isHistory, names, native)
            if (!fragment.isAdded) return@launch
            actions.forEach { e -> card.addView(rowFor(fragment, e, material)) }
        }

        // The rounded frame is the (fixed) ScrollView; rows scroll INSIDE it.
        val scroll = ScrollView(ctx).apply {
            if (material) {
                background = GradientDrawable().apply { setColor(M3.surfaceContainer); cornerRadius = M3.radiusLg }
                setClipToOutlineCompat(true)
                setPadding(0, 6.dp, 0, 6.dp)
            }
            isVerticalScrollBarEnabled = false
            isClickable = true
            addView(card, FrameLayout.LayoutParams(MP, WC))
        }
        // 圆屏模式：菜单卡片内收进圆屏安全区（左右 + 上下各留安全边距），条目不被圆边裁切；
        // 内容过高时 ScrollView 在安全区内滚动，保证所有选项都能滚到、完整可点。
        val round = momoi.mod.qqpro.lib.RoundWatch.enabled
        val sideM = if (round) maxOf(14.dp, momoi.mod.qqpro.lib.RoundWatch.horizontalInsetPx(ctx)) else 14.dp
        val topM = if (round) maxOf(14.dp, momoi.mod.qqpro.lib.RoundWatch.safeTopBottomPx(ctx)) else 14.dp
        val scrim = FrameLayout(ctx).apply {
            setBackgroundColor(0x99_000000.toInt())
            setOnClickListener { runCatching { fragment.dismiss() } }
            addView(scroll, FrameLayout.LayoutParams(MP, WC, Gravity.CENTER).apply {
                leftMargin = sideM; rightMargin = sideM; topMargin = topM; bottomMargin = topM
            })
        }
        return SwipeBackLayout(ctx).apply {
            onSwipeBack = { runCatching { fragment.dismiss() } }
            addView(scrim, FrameLayout.LayoutParams(MP, MP))
        }
    }

    private fun rowFor(fragment: AIOLongClickMenuFragment, e: Entry, material: Boolean): View =
        row(fragment.requireContext(), e, material) { runCatching { e.action() }; runCatching { fragment.dismiss() } }

    private fun row(ctx: Context, e: Entry, material: Boolean, onClick: () -> Unit): View {
        // Material: accent/onSurface on a flush ripple row inside the surface card.
        // Non-material: white text + white icon (red for destructive) on a semi-transparent dark pill.
        val red = 0xFF_FF6B6B.toInt()
        val tint = if (e.destructive) (if (material) M3.error else red) else (if (material) M3.primary else Color.WHITE)
        val textColor = if (e.destructive) (if (material) M3.error else red) else (if (material) M3.onSurface else Color.WHITE)
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(18.dp, 12.dp, 18.dp, 12.dp)
            setMinimumHeight(56.dp)  // MD3 菜单项最小触控高度
            isClickable = true
            background = if (material) {
                M3.ripple(null, 0x33_888888)
            } else {
                val pill = GradientDrawable().apply { setColor(0x80_242424.toInt()); cornerRadius = 14.dp.toFloat() }
                M3.ripple(pill, 0x33_FFFFFF)
            }
            setOnClickListener { onClick() }
            if (!material) {
                layoutParams = LinearLayout.LayoutParams(MP, WC).apply {
                    leftMargin = 10.dp; rightMargin = 10.dp; topMargin = 3.dp; bottomMargin = 3.dp
                }
            }
            addView(ImageView(ctx).apply { setImageDrawable(MaterialSymbol(e.symbol, tint)) },
                LinearLayout.LayoutParams(24.dp, 24.dp))  // MD3 菜单项前导图标 24dp
            addView(TextView(ctx).apply { text = e.label; setTextColor(textColor); textSize = 15f },
                LinearLayout.LayoutParams(0, WC, 1f).apply { marginStart = 18.dp })
        }
    }

    private const val MP = ViewGroup.LayoutParams.MATCH_PARENT
    private const val WC = ViewGroup.LayoutParams.WRAP_CONTENT
}
