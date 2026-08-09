package momoi.mod.qqpro.hook

import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.TextView
import java.lang.ref.WeakReference
import com.tencent.qqnt.watch.add.QQAddFriendFragment
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.util.Utils

/** Bundle key carrying a number to prefill into the add/search numeric pad. */
const val EXTRA_SEARCH_PREFILL = "qqpro_search_prefill"

/**
 * Opens the add-friend/group numeric search pad with [code] prefilled, navigating
 * via the (obfuscated) androidx NavController resolved from this view's tree.
 * Shared by the group-invite card (StructMsgView) and the contact name card. The
 * search returns both friends and groups, so a uin resolves the contact and a
 * group code resolves the group. [type] mirrors the fragment arg (1 = group).
 */
fun View.openAddSearch(code: String, type: Int = 1) {
    try {
        val nav = findNavControllerFromTree() ?: run {
            Utils.log("openAddSearch: NavController not found in view tree")
            return
        }
        val actionId = resources.getIdentifier(
            "select_fragment_to_add_friend", "id", context.packageName
        )
        if (actionId == 0) {
            Utils.log("openAddSearch: select_fragment_to_add_friend id not found")
            return
        }
        val args = Bundle().apply {
            putInt("type", type)
            putString(EXTRA_SEARCH_PREFILL, code)
        }
        // navigate(int destId, Bundle args, NavOptions options) — name obfuscated.
        val navigate = nav.javaClass.methods.firstOrNull { m ->
            val p = m.parameterTypes
            p.size == 3 && p[0] == Int::class.javaPrimitiveType && p[1] == Bundle::class.java
        } ?: run {
            Utils.log("openAddSearch: navigate(int,Bundle,..) not found on ${nav.javaClass.name}")
            return
        }
        navigate.invoke(nav, actionId, args, null)
    } catch (e: Exception) {
        Utils.log("openAddSearch error: ${e.message}")
    }
}

/** Replicates androidx Navigation.findNavController(View) without the (obfuscated) static. */
internal fun View.findNavControllerFromTree(): Any? {
    val tagId = resources.getIdentifier("nav_controller_view_tag", "id", context.packageName)
    if (tagId == 0) return null
    var v: View? = this
    while (v != null) {
        when (val tag = v.getTag(tagId)) {
            is WeakReference<*> -> tag.get()?.let { return it }
            null -> {}
            else -> return tag
        }
        v = v.parent as? View
    }
    return null
}

/**
 * When the add-friend/search numeric pad ([QQAddFriendFragment]) is opened with a
 * [EXTRA_SEARCH_PREFILL] argument (e.g. from tapping a group-invite card), prefill
 * the typed value so the user only has to hit the search button. Searching also
 * returns groups, so a group code resolves the invited group.
 */
@Mixin
class AddFriendPrefill : QQAddFriendFragment() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // The Material add-by-number page (QQAddFriendMaterial) rebuilds this screen and handles the
        // prefill itself against its own field; this native-field wiring would just fight it.
        if (Settings.materialContactsList.value) return
        // Enable pasting a number into the search field. The numeric pad drives `curString` (g),
        // which is what the search reads; the EditText (e) is just display — so a raw paste never
        // reached the search. setupPaste keeps g synced with the field and adds a long-press paste.
        try {
            setupPaste(this, e)
        } catch (ex: Exception) {
            Utils.log("AddFriendPrefill paste setup error: ${ex.message}")
        }
        val code = arguments?.getString(EXTRA_SEARCH_PREFILL)
        if (code.isNullOrEmpty()) return
        try {
            // g = curString (drives the search), e = showText (the visible field)
            g = code
            e.text = code
        } catch (ex: Exception) {
            Utils.log("AddFriendPrefill error: ${ex.message}")
        }
    }
}

/**
 * Make paste work on the add-friend search field. Lives OUTSIDE the @Mixin class on purpose: the
 * anonymous [TextWatcher] / [View.OnLongClickListener] below must compile into this (normal) file's
 * class, not be copied into the target fragment — anonymous classes declared inside a @Mixin body
 * crash at runtime with IllegalAccessError (see the qqpro-mixin-anon-class note).
 *
 *  - A [TextWatcher] mirrors whatever ends up in the field into [QQAddFriendFragment.g] (curString,
 *    the value the search actually uses), so a system paste-menu paste also takes effect.
 *  - A long-press handler pastes the clipboard's digits directly (guaranteed affordance even if the
 *    native paste menu doesn't show on the watch).
 */
internal fun setupPaste(fragment: QQAddFriendFragment, edit: TextView) {
    edit.addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) {
            fragment.g = s?.toString().orEmpty().filter { it.isDigit() }.take(11)
        }
        override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
    })
    edit.isLongClickable = true
    edit.setOnLongClickListener(object : View.OnLongClickListener {
        override fun onLongClick(v: View): Boolean {
            val ctx = v.context
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val raw = cm?.primaryClip?.takeIf { it.itemCount > 0 }
                ?.getItemAt(0)?.coerceToText(ctx)?.toString().orEmpty()
            val digits = raw.filter { it.isDigit() }.take(11)
            if (digits.isEmpty()) {
                Utils.toast(ctx, "剪贴板没有可粘贴的号码")
                return true
            }
            edit.text = digits          // triggers the TextWatcher → keeps curString in sync
            fragment.g = digits
            Utils.toast(ctx, "已粘贴")
            return true
        }
    })
}
