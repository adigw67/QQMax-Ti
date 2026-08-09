package momoi.mod.qqpro.hook.aio_cell

import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.tencent.qqnt.kernel.nativeinterface.IOperateCallback
import com.tencent.watch.aio_impl.data.WatchAIOMsgItem
import com.tencent.watch.aio_impl.ui.widget.AIOCellGroupWidget
import com.tencent.watch.ime.util.ImeTextUtil
import momoi.mod.qqpro.MsgUtil
import momoi.mod.qqpro.enums.ElementType
import momoi.mod.qqpro.enums.NTMsgType
import momoi.mod.qqpro.hook.action.CurrentContact
import momoi.mod.qqpro.hook.action.CurrentMsgList
import momoi.mod.qqpro.lib.Observable
import momoi.mod.qqpro.lib.WRAP
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.warp
import java.lang.ref.WeakReference
import java.util.WeakHashMap

object PlusOneButton {
    // One button instance, moved between cells as the last matching message changes.
    private var button: TextView? = null
    // Tracks which widget's warp container currently holds the button.
    private var attachedTo: WeakReference<AIOCellGroupWidget>? = null
    // Per-widget warp containers, created once on first qualifying bind.
    private val warps = WeakHashMap<AIOCellGroupWidget, LinearLayout>()
    // msgId of the item each widget is currently bound to. Lets a list change (e.g. a
    // recall) relocate the button to the new effective-last message's widget WITHOUT that
    // cell having to rebind — the cell sitting before a recall graytip never rebinds, so
    // bind() alone would leave the +1 stale.
    private val boundMsgId = WeakHashMap<AIOCellGroupWidget, Long>()
    // The msgList Observable instance we're subscribed to. It is replaced per chat
    // (CurrentMsgList.Clear), so re-subscribe whenever it changes.
    private var observed: Observable<MutableList<WatchAIOMsgItem>>? = null

    private fun msgText(item: WatchAIOMsgItem): String? {
        if (item.d.msgType == NTMsgType.GRAYTIPS) return null
        val elements = item.d.elements ?: return null
        val text = elements.mapNotNull {
            if (it.elementType == ElementType.TEXT) it.textElement?.content else null
        }.joinToString("").trim()
        return text.takeIf { it.isNotEmpty() }
    }

    // Index of the last real message, skipping trailing graytips (e.g. recall tips).
    // A recall turns the original message into a graytip (msgType=5); the message that
    // precedes it then becomes the effective latest message for +1 purposes.
    private fun effectiveLastIndex(list: List<WatchAIOMsgItem>): Int {
        for (i in list.indices.reversed()) {
            if (list[i].d.msgType != NTMsgType.GRAYTIPS) return i
        }
        return -1
    }

    // Index of the last real (non-graytip) message strictly before [before]. Used to find
    // the message to compare the latest one against, skipping any graytips in between.
    private fun effectivePrevIndex(list: List<WatchAIOMsgItem>, before: Int): Int {
        for (i in before - 1 downTo 0) {
            if (list[i].d.msgType != NTMsgType.GRAYTIPS) return i
        }
        return -1
    }

    private fun detach() {
        (button?.parent as? ViewGroup)?.removeView(button)
        attachedTo = null
    }

    private fun ensureObserving() {
        val cur = CurrentMsgList.msgList
        if (observed === cur) return
        observed = cur
        // Re-evaluate the button placement on every list change (new msg, recall, delete).
        cur.observe { refresh() }
    }

    // Re-evaluate +1 against the current list, independent of cell binds. Used when the
    // list changes but the affected cell doesn't rebind (recall before the tail, etc.).
    private fun refresh() {
        if (!momoi.mod.qqpro.Settings.plusOneButton.value) { detach(); return }
        val list = CurrentMsgList.msgList.value
        val idx = effectiveLastIndex(list)
        val text = if (idx >= 0) msgText(list[idx]) else null
        val prevIdx = if (text != null) effectivePrevIndex(list, idx) else -1
        val prevText = if (prevIdx >= 0) msgText(list[prevIdx]) else null
        val shouldShow = text != null && text == prevText

        Utils.log("+1 refresh: lastIdx=$idx/${list.size} shouldShow=$shouldShow")

        if (!shouldShow) {
            detach()
            return
        }
        // Find the widget currently bound to the effective-last message (by stable msgId).
        // If its cell isn't on screen yet, drop any stale button — bind() re-adds it when
        // the cell binds.
        val target = boundMsgId.entries.firstOrNull { it.value == list[idx].d.msgId }?.key
        if (target == null) {
            detach()
            return
        }
        place(target, text!!)
    }

    fun bind(widget: AIOCellGroupWidget, item: WatchAIOMsgItem) {
        if (!momoi.mod.qqpro.Settings.plusOneButton.value) {
            if (attachedTo?.get() === widget) detach()
            return
        }
        ensureObserving()
        boundMsgId[widget] = item.d.msgId

        val list = CurrentMsgList.msgList.value
        val idx = list.indexOf(item)
        val isLast = idx >= 0 && idx == effectiveLastIndex(list)

        val text = if (isLast) msgText(item) else null
        val prevIdx = if (text != null) effectivePrevIndex(list, idx) else -1
        val prevText = if (prevIdx >= 0) msgText(list[prevIdx]) else null
        val shouldShow = text != null && text == prevText

        Utils.log("+1 bind: idx=$idx/${list.size} isLast=$isLast shouldShow=$shouldShow")

        if (!shouldShow) {
            if (isLast || attachedTo?.get() === widget) detach()
            return
        }
        place(widget, text!!)
    }

    private fun place(widget: AIOCellGroupWidget, text: String) {
        // Get or create the warp container for this widget's content.
        val warp = warps[widget] ?: run {
            val content = widget.getContentWidget<View>() as? TextView ?: return
            // Reuse the vertical container if LinkPreview / a special-message hook already created
            // one (in which case that owner manages the content's width — e.g. LinkPreview needs
            // FILL so the text fills the preview column).
            val container = if (content.parent is LinearLayout) {
                content.parent as LinearLayout
            } else {
                // +1 created the wrapper itself → no full-width sibling card. warp() defaults the
                // wrapped child to FILL/weight, which makes the bubble collapse to the +1 button's
                // width (a long "Testing testing" rendered as a button-wide "Te"). Force the text to
                // hug its content so the bubble keeps its natural text width and the pill sits at the
                // bottom-right.
                content.warp().also {
                    content.layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
                }
            }
            warps[widget] = container
            container
        }

        val btn = button ?: TextView(widget.context).apply {
            setText("+1")
            setTextColor(M3.onSurface)
            textSize = 10f
            gravity = Gravity.CENTER
            // Rounded translucent-dark pill so the "+1" stays visible even when the bubble
            // color is set close to the text color (a bare blue glyph vanished on blue bubbles).
            setPadding(12, 2, 12, 2)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                setColor(0x66000000)
                cornerRadius = 100f
            }
        }.also { button = it }

        if (btn.parent !== warp) {
            (btn.parent as? ViewGroup)?.removeView(btn)
            // Wrap-width pill, right-aligned, instead of a full-width row.
            warp.addView(btn, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                topMargin = 2
                gravity = Gravity.END
            })
        }
        btn.visibility = View.VISIBLE
        attachedTo = WeakReference(widget)

        btn.setOnClickListener {
            Utils.log("+1: sending text=$text")
            val elements = ImeTextUtil.a.b(text)
            MsgUtil.msgService.sendMsg(
                CurrentContact, 0L, elements,
                IOperateCallback { code, errMsg -> Utils.log("+1 send result=$code $errMsg") }
            )
        }
    }
}
