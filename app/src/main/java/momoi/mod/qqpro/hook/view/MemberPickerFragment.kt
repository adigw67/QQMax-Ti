package momoi.mod.qqpro.hook.view
import android.os.Build
import momoi.mod.qqpro.lib.setClipToOutlineCompat

import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.tencent.qqnt.kernel.nativeinterface.MemberInfo
import loadPicUrl
import momoi.mod.qqpro.hook.action.CurrentGroupMembers
import momoi.mod.qqpro.lib.FILL
import momoi.mod.qqpro.lib.clickable
import momoi.mod.qqpro.lib.content
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.gravity
import momoi.mod.qqpro.lib.linearLayout
import momoi.mod.qqpro.lib.margin
import momoi.mod.qqpro.lib.marginHorizontal
import momoi.mod.qqpro.lib.padding
import momoi.mod.qqpro.lib.text
import momoi.mod.qqpro.lib.textColor
import momoi.mod.qqpro.lib.textSize
import momoi.mod.qqpro.lib.vertical
import momoi.mod.qqpro.lib.width
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.util.Utils
import moye.wearqq.AtElementArg
import moye.wearqq.IMEOperation

/**
 * Group-only member picker for the "@成员" panel option. Lists the current group's members
 * (no invite/add button — selection only). Picking a member inserts an @mention into the
 * input box and opens the input method, the same way the native @ flow does.
 */
class MemberPickerFragment : MyDialogFragment() {

    /**
     * uid="all" is QQ's marker for @全体成员 (mention everyone). [name] is the list display label;
     * [atNick] is the nickname handed to the @mention (the IME prepends "@" itself, so this must
     * NOT include a leading "@" — otherwise it renders as "@@全体成员").
     */
    private data class Entry(val uid: String, val uin: Long, val name: String, val atNick: String = name)

    /** All entries (unfiltered). The "@全体成员" entry is always first. */
    private var allEntries: List<Entry> = emptyList()
    private lateinit var list: RecyclerView

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val ctx = inflater.context
        val root = LinearLayout(ctx).vertical()
        root.setBackgroundColor(M3.surface)

        allEntries = buildEntries()
        Utils.log("MemberPicker: ${allEntries.size} entries")

        root.content {
            add<TextView>()
                .text("选择要@的成员")
                .textSize(15f)
                .textColor(M3.onSurface)
                .gravity(Gravity.CENTER)
                .width(FILL)
                .padding(top = 14.dp, bottom = 10.dp)

            add<EditText>()
                .textSize(14f)
                .textColor(M3.onSurface)
                .width(FILL)
                .padding(left = 12.dp, top = 8.dp, right = 12.dp, bottom = 8.dp)
                .marginHorizontal(16.dp)
                .margin(bottom = 8.dp)
                .apply {
                    hint = "搜索成员（昵称或 QQ 号）"
                    setHintTextColor(M3.hint)
                    setSingleLine()
                    background = GradientDrawable().apply {
                        setColor(M3.surfaceContainer)
                        cornerRadius = 12.dp.toFloat()
                    }
                    addTextChangedListener(object : TextWatcher {
                        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                        override fun afterTextChanged(s: Editable?) {
                            render(s?.toString().orEmpty())
                        }
                    })
                }

            list = add<RecyclerView>().linearLayout()
            (list.layoutParams as LinearLayout.LayoutParams).apply {
                width = FILL
                height = 0
                weight = 1f
            }
        }
        render("")
        return root
    }

    /** (Re)build the list, keeping only entries that match [query] (matches name or QQ number). */
    private fun render(query: String) {
        val q = query.trim()
        val shown = if (q.isEmpty()) {
            allEntries
        } else {
            allEntries.filter {
                it.name.contains(q, ignoreCase = true) || it.uin.toString().contains(q)
            }
        }
        list.content(
            data = shown,
            factory = { rowView() },
            update = { entry ->
                val avatar = getChildAt(0) as ImageView
                val name = getChildAt(1) as TextView
                name.text = entry.name
                if (entry.uid == "all") {
                    avatar.setImageDrawable(null)
                    avatar.setBackgroundColor(M3.primary)
                } else {
                    avatar.setBackgroundColor(M3.outlineVariant)
                    avatar.loadPicUrl("https://q.qlogo.cn/headimg_dl?dst_uin=${entry.uin}&spec=100")
                }
                clickable { pick(entry) }
            }
        )
    }

    private fun buildEntries(): List<Entry> {
        val members = CurrentGroupMembers.info?.values
            ?.map { m: MemberInfo ->
                val name = m.remark.ifBlank { m.cardName.ifBlank { m.nick.ifBlank { m.uin.toString() } } }
                Entry(m.uid, m.uin, name)
            }
            ?.sortedBy { it.name }
            .orEmpty()
        return listOf(Entry("all", 0L, "@全体成员", atNick = "全体成员")) + members
    }

    private fun pick(entry: Entry) {
        runCatching {
            IMEOperation.INSTANCE.openIMEWithExtra(AtElementArg(entry.uid, entry.atNick, ""))
            Utils.log("MemberPicker: picked ${entry.name} uid=${entry.uid}")
        }.onFailure { Utils.log("MemberPicker: pick failed: $it") }
        dismiss()
    }

    /** A row: circular avatar on the left, member name on the right. */
    private fun rowView(): View {
        val ctx = requireContext()
        val row = LinearLayout(ctx)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        row.layoutParams = LinearLayout.LayoutParams(FILL, ViewGroup.LayoutParams.WRAP_CONTENT)
        row.setPadding(16.dp, 8.dp, 16.dp, 8.dp)

        val avatar = ImageView(ctx)
        avatar.layoutParams = LinearLayout.LayoutParams(36.dp, 36.dp)
        avatar.scaleType = ImageView.ScaleType.CENTER_CROP
        avatar.setClipToOutlineCompat(true)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            avatar.outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
        }
        row.addView(avatar)

        val name = TextView(ctx)
        name.textSize = 14f
        name.setTextColor(M3.onSurface)
        name.gravity = Gravity.CENTER_VERTICAL
        name.layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            marginStart = 12.dp
        }
        row.addView(name)
        return row
    }
}
