package momoi.mod.qqpro.hook.contact

import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.TextView
import com.tencent.qqnt.watch.add.result.FriendDetailData
import com.tencent.qqnt.watch.add.result.QQSearchFriendFragment
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.util.Utils

/**
 * Add-friend search result page ([QQSearchFriendFragment], layout `fragment_friend_detail`): the
 * `self_name` is a plain [TextView] sized `wrap_content` inside a ConstraintLayout, so a long name
 * is laid out on a single over-wide line and clipped at the screen edge. Bind it to the parent
 * (width = 0 → MATCH_CONSTRAINT) and allow a few lines so it wraps and centers instead.
 *
 * Opt-in via [Settings.profileNameMultiline] (the same toggle as the other profile pages). The
 * constructor is never run by ApkMixin; the dummy [FriendDetailData] only satisfies the (sole)
 * super-constructor signature so this compiles.
 */
@Mixin
class SearchResultName :
    QQSearchFriendFragment(FriendDetailData("", "", "", false, "", false)) {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // When the contacts-list materialization is on, QQSearchFriendMaterial rebuilds this whole page
        // (re-parenting self_name into a LinearLayout). Setting width=0 here would then mean 0px (no
        // weight) and collapse the name — so leave it to the materializer.
        if (Settings.materialContactsList.value) return
        if (!Settings.profileNameMultiline.value) return
        try {
            val id = resources.getIdentifier("self_name", "id", requireContext().packageName)
            val name = (if (id != 0) view.findViewById<View>(id) else null) as? TextView ?: return
            // width = 0 is MATCH_CONSTRAINT under ConstraintLayout (start+end are constrained to the
            // parent in the layout), so the text wraps within the screen instead of overflowing.
            name.layoutParams = name.layoutParams.apply { width = 0 }
            name.gravity = Gravity.CENTER
            name.isSingleLine = false
            name.maxLines = 4
            name.ellipsize = TextUtils.TruncateAt.END
            name.setPadding(12.dp, 0, 12.dp, 0)
            Utils.log("SearchResultName: self_name set multiline")
        } catch (e: Exception) {
            Utils.log("SearchResultName error: ${e.message}")
        }
    }
}
