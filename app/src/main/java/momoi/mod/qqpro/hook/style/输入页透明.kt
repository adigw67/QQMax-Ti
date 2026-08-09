package momoi.mod.qqpro.hook.style

import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.tencent.watch.ime.InputMethodFragment
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.hook.action.MessageEdit
import momoi.mod.qqpro.asGroup
import momoi.mod.qqpro.drawable.closeIconDrawable
import momoi.mod.qqpro.drawable.roundCornerDrawable
import momoi.mod.qqpro.drawable.sendIconDrawable
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.dpf
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.lib.material.M3

@Mixin
class 输入页透明 : InputMethodFragment() {
    override fun Y(p1: LayoutInflater?, p2: ViewGroup?, p3: Bundle?): View? {
        return super.Y(p1, p2, p3)?.apply {
            setBackgroundColor(0x77_000000)
            runCatching { modernizeInput(asGroup()) }
                .onFailure { Utils.log("modernizeInput failed: $it") }
        }
    }

    private fun modernizeInput(root: ViewGroup) {
        val editText = root.findViewById<EditText>(0x7e090483) // inputText

        // Material-style input field: rounded translucent background with comfortable padding.
        // Applied unconditionally.
        editText.background = roundCornerDrawable(0x22_FFFFFF, 18.dpf)
        editText.setPadding(14.dp, 10.dp, 14.dp, 10.dp)

        if (MessageEdit.editingMsgId != 0L) {
            showEditBanner(root)
        }

        if (Settings.inlineSendButton.value) {
            applyInlineSendButton(root, editText)
        }
    }

    // When editing one of our own messages, reuse the native reply banner (input_tip)
    // to show an "编辑消息" label with a cancel (X) button. Cancelling drops the
    // edit linkage so the typed text sends as a normal new message instead.
    private fun showEditBanner(root: ViewGroup) {
        val res = root.resources
        val pkg = root.context.packageName
        val tip = root.findViewById<View>(res.getIdentifier("input_tip", "id", pkg))
        val label = root.findViewById<TextView>(res.getIdentifier("input_tip_label", "id", pkg))
        val clear = root.findViewById<View>(res.getIdentifier("clear_extra", "id", pkg))
        tip?.visibility = View.VISIBLE
        clear?.visibility = View.VISIBLE
        label?.text = "编辑消息"
        clear?.setOnClickListener {
            MessageEdit.consume()
            tip?.visibility = View.GONE
        }
    }

    private fun applyInlineSendButton(root: ViewGroup, editText: EditText) {
        val confirm = root.findViewById<View>(0x7e090280)      // confirm_btn (发送)
        val ctx = root.context

        // Move the EditText out of its vertical container and into a new horizontal row.
        val column = editText.parent.asGroup()
        val index = column.indexOfChild(editText)
        column.removeViewAt(index)

        // Hide the now-duplicated text "发送" button in the bottom row.
        confirm.visibility = View.GONE

        val accentBg = roundCornerDrawable(M3.primary, 9999f)

        val close = ImageView(ctx).apply {
            setImageDrawable(closeIconDrawable())
            setPadding(9.dp, 9.dp, 9.dp, 9.dp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener {
                // Close the input page and cancel sending.
                @Suppress("DEPRECATION")
                activity?.onBackPressed()
            }
        }
        val send = ImageView(ctx).apply {
            setImageDrawable(sendIconDrawable())
            background = accentBg
            setPadding(9.dp, 9.dp, 9.dp, 9.dp)
            scaleType = ImageView.ScaleType.FIT_CENTER
            setOnClickListener { confirm.callOnClick() }
        }

        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            // Keep the buttons pinned to the bottom so they stay on screen
            // when the EditText grows with multiline text.
            gravity = Gravity.BOTTOM
            addView(close, LinearLayout.LayoutParams(40.dp, 40.dp))
            addView(
                editText,
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = 4.dp
                    marginEnd = 4.dp
                }
            )
            addView(send, LinearLayout.LayoutParams(40.dp, 40.dp))
        }

        column.addView(
            row,
            index,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        )
    }
}
