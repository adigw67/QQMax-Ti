package momoi.mod.qqpro.hook

import android.content.Context
import android.text.TextPaint
import android.view.View
import com.tencent.qqnt.graytips.HighlightItem
import com.tencent.qqnt.graytips.action.BaseUserActionInfo
import com.tencent.qqnt.graytips.span.HighlightClickableSpan
import momoi.anno.mixin.ConstructorHook
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.util.linkColorResolved
import mqq.app.AppRuntime

/**
 * Make member names inside chat grey tips ("X 撤回了一条消息", "X 邀请 Y 加入群聊", group
 * name changes, etc.) tappable — tapping opens the member's profile card, like the
 * existing @member feature ([parseAtMembers]). Gated on [Settings.parseAtMember].
 *
 * The names are already built as clickable [HighlightClickableSpan]s during grey-tip
 * decoding (JSON "qq" items, XML, revoke) and carry the member uid — but the watch build
 * (a) discards that uid in [BaseUserActionInfo]'s constructor and (b) ships a gutted
 * [HighlightClickableSpan.onClick] that only logs, and the cell never sets a movement
 * method. We fix all three so the span is created already-functional, with the correct
 * uid, rather than patching the rendered TextView afterwards:
 *
 *  1. [BaseUserActionInfoHook] re-captures the uid the constructor would otherwise drop,
 *     so every action-info path (JSON/XML/revoke/group decoders) carries it.
 *  2. [HighlightClickableSpanHook] makes onClick open that uid's profile card.
 *  3. The grey-tip TextView gets a LinkMovementMethod so the spans are clickable — done
 *     in [AIOCell.HookCell.i] (the grey-tip cell's root view IS the bare TextView), since
 *     hooking WatchGrayTipsCell directly is blocked by a Kotlin stub/abstract `d` clash.
 */

/**
 * Restore the uid that [BaseUserActionInfo]'s constructor accepts but otherwise discards
 * (the watch R8 build stores none of its three args). Every user-name action info
 * (UserNormalActionInfo / UserForOpenFriendProfileActionInfo) extends this, so storing it
 * here covers all grey-tip kinds. [profileUid] is read back in [HighlightClickableSpanHook].
 */
@Mixin
class BaseUserActionInfoHook(uid: String, nick: String, uin: String) :
    BaseUserActionInfo(uid, nick, uin) {

    @JvmField
    var profileUid: String? = null

    // Spliced onto the end of BaseUserActionInfo.<init>(String,String,String): p1 == uid.
    @ConstructorHook
    fun storeProfileUid(uid: String?, nick: String?, uin: String?) {
        profileUid = uid
    }
}

/**
 * Make the (otherwise gutted) grey-tip span click open the member's profile when it carries
 * a [BaseUserActionInfoHook.profileUid]. Other action kinds (URL/scheme) fall through to the
 * original behaviour.
 */
@Mixin
class HighlightClickableSpanHook(
    runtime: AppRuntime?, color: Int, ctx: Context?, item: HighlightItem?
) : HighlightClickableSpan(runtime, color, ctx, item) {

    override fun onClick(widget: View) {
        if (Settings.parseAtMember.value) {
            val uid = memberUid()
            if (!uid.isNullOrEmpty()) {
                widget.openMemberProfileByUid(uid)
                return
            }
        }
        super.onClick(widget)
    }

    // Paint member-name spans in the @-link blue so they look consistent regardless of the
    // grey-tip kind: the revoke path builds its span with a non-blue color (type 3/1), unlike
    // the invite/JSON path. Other action kinds keep their original color.
    override fun updateDrawState(ds: TextPaint) {
        super.updateDrawState(ds)
        if (Settings.parseAtMember.value && !memberUid().isNullOrEmpty()) {
            ds.color = linkColorResolved()
            ds.isUnderlineText = false
        }
    }

    // HighlightClickableSpan.d = HighlightItem; HighlightItem.d = actionInfo.
    private fun memberUid(): String? =
        ((this.d)?.d as? BaseUserActionInfoHook)?.profileUid
}
