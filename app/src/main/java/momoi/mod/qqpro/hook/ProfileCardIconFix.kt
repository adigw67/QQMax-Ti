package momoi.mod.qqpro.hook

import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.tencent.qqnt.kernel.api.IKernelService
import com.tencent.qqnt.watch.profile.ProfileData
import moye.wearqq.AtElementArg
import moye.wearqq.IMEOperation
import com.tencent.qqnt.watch.profile.ui.ProfileCardFragment
import com.tencent.widget.SingleLineTextView
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.hook.action.CurrentContact
import momoi.mod.qqpro.hook.action.isGroup
import momoi.mod.qqpro.hook.contact.ProfileNameView
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.util.Utils
import mqq.app.MobileQQ

/**
 * Tweaks for the contact/member profile card ([ProfileCardFragment]):
 *  1. "去聊天" button — replace the fixed-size [R.id.goto_chat] icon with a 💬 emoji so it tracks the
 *     text size (see [fixGotoChatIcon]).
 *  2. Show the contact's actual QQ nickname under the displayed (group card) name when they differ.
 *  3. Long-press the name / QQ id to copy the full value (works even when it's ellipsized on screen).
 */
@Mixin
class ProfileCardIconFix : ProfileCardFragment() {
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Native onViewCreated does `view.findViewById(R.id.at_btn).setOnClickListener(...)`
        // unconditionally. When this fragment's view is reused after our rich rebuild has already
        // re-parented at_btn into its own tree, that findViewById returns null → the native code NPEs
        // and crashes the app. Guard it: the rich build below is idempotent (returns early when the
        // page is already built), so the displayed profile is unaffected.
        runCatching { super.onViewCreated(view, savedInstanceState) }
            .onFailure { Utils.log("ProfileCardFragment super.onViewCreated threw (likely at_btn null on view reuse): ${it.message}") }
        // 长按头像保存大图。在 rich/legacy 分支之前绑定:rich 重建会把同一个 avatar 视图 re-parent 进新
        // 视图树,监听器跟随视图实例保留,不受影响。
        runCatching {
            val ctx = requireContext()
            val uin = arguments?.getParcelable<ProfileData>("profile_data")?.d.orEmpty()
            val avatar = view.findViewById<View>(resources.getIdentifier("avatar", "id", ctx.packageName))
            AvatarSave.attach(avatar) { AvatarSave.userUrl(uin) }
        }.onFailure { Utils.log("ProfileCardIconFix avatar-save bind error: ${it.message}") }
        if (Settings.useRichProfile.value || Settings.materialContactsList.value) {
            // Full Material rebuild: re-parents the native views into a new tree (own scroll + info card).
            try {
                val ctx = requireContext()
                val profile = arguments?.getParcelable<ProfileData>("profile_data")
                val uid = profile?.e.orEmpty()
                val name = profile?.f
                    ?: view.findViewById<SingleLineTextView>(
                        resources.getIdentifier("nickname", "id", ctx.packageName)
                    )?.text?.toString().orEmpty()
                val uin = profile?.d.orEmpty()
                // Real friend status drives the primary 去聊天/加好友 button. ProfileData.isFriend (g)
                // is set at navigation time via IContactRuntimeService.isFriend, so it's authoritative
                // — and the native goto_chat handler is wired from the same field, so they stay in sync.
                val isFriend = profile?.g ?: false
                // Was the card opened from a group? The member-open path (startMemberProfileCard and our
                // own group/grey-tip openers) builds ProfileData with birthday "0-0" + gender -1; the
                // regular contact/DM path (startProfileCard) carries real values. Combined with an active
                // group chat, this means the 艾特Ta button only appears for a group member — never in a
                // DM, the add-friend/search flow, or a QZone-opened card.
                val fromGroup = CurrentContact.isGroup && profile?.b == "0-0" && profile.c == -1
                // @-mention action: stage the @ and pop back to the chat (framework onBackPressed, not
                // the obfuscated NavController), letting the chat's onResume fire openIME → inline @.
                val atAction = {
                    runCatching {
                        IMEOperation.INSTANCE.clearExtra()
                        IMEOperation.INSTANCE.setExtra(AtElementArg(uid, name, ""))
                        RichProfilePage.pendingAt = true
                        requireActivity().onBackPressed()
                    }.onFailure { Utils.log("profile @ action failed: $it") }
                    Unit
                }
                RichProfilePage.build(view, ctx, uid, name, uin, isFriend, fromGroup, atAction)
            } catch (e: Exception) {
                Utils.log("ProfileCardIconFix rich build error: ${e.message}")
            }
            return
        }
        // Legacy path: keep the original page with only the minor enrich tweaks.
        fixGotoChatIcon(view)
        enrichProfile(view)
        if (Settings.profileNameMultiline.value) makeNameMultiline(view)
    }

    /**
     * Opt-in ([Settings.profileNameMultiline]): show the card's name on multiple lines instead of
     * the single-line widget that truncates with "…". The actual view-swap + mirror lives in
     * [ProfileNameView.enhanceCardName] — anonymous listeners declared inside a @Mixin body crash
     * with IllegalAccessError, so it must run from a non-mixin helper class.
     */
    private fun makeNameMultiline(view: View) {
        try {
            val pkg = requireContext().packageName
            val nickView = view.findViewById<View>(
                resources.getIdentifier("nickname", "id", pkg)
            ) as? SingleLineTextView ?: return
            val qqView = view.findViewById<TextView>(resources.getIdentifier("self_qq", "id", pkg))
            val color = qqView?.currentTextColor ?: M3.onSurface
            ProfileNameView.enhanceCardName(nickView, color)
        } catch (e: Exception) {
            Utils.log("ProfileCardIconFix multiline error: ${e.message}")
        }
    }

    private fun fixGotoChatIcon(view: View) {
        try {
            val id = resources.getIdentifier("goto_chat", "id", requireContext().packageName)
            val btn = (if (id != 0) view.findViewById<View>(id) else null) as? MaterialButton ?: return
            // Only the friend ("去聊天") state ships the chat icon; the "加好友" state has none.
            if (btn.icon == null) return
            btn.transformationMethod = null
            // Real Material chat icon in the MaterialButton icon slot, hugging the centered label
            // (was the 💬 emoji text prefix). ICON_GRAVITY_TEXT_START keeps it next to the text.
            val tint = btn.currentTextColor
            btn.icon = MaterialSymbol(MaterialSymbols.chat_bubble, tint).apply { setBounds(0, 0, 18.dp, 18.dp) }
            btn.setIconGravity(2)   // ICON_GRAVITY_TEXT_START (constant not in the compile stub)
            btn.iconSize = 18.dp
            btn.iconPadding = 6.dp
            btn.setIconTint(android.content.res.ColorStateList.valueOf(tint))
        } catch (e: Exception) {
            Utils.log("ProfileCardIconFix icon error: ${e.message}")
        }
    }

    private fun enrichProfile(view: View) {
        try {
            val ctx = requireContext()
            val pkg = ctx.packageName
            // nickname is a com.tencent.widget.SingleLineTextView, which extends View (NOT TextView) —
            // so keep it as a View; only self_qq is a real TextView.
            val nickView: View? = view.findViewById(resources.getIdentifier("nickname", "id", pkg))
            val qqView = view.findViewById<TextView>(resources.getIdentifier("self_qq", "id", pkg))

            // ProfileData fields (obfuscated): d=uin, e=uid, f=nickName.
            val profile = arguments?.getParcelable<ProfileData>("profile_data")
            val displayName = profile?.f
                ?: (nickView as? SingleLineTextView)?.text?.toString().orEmpty()
            val uin = profile?.d ?: qqView?.text?.toString()?.trim().orEmpty()
            val uid = profile?.e
            Utils.log("ProfileCardIconFix.enrich: nickView=$nickView qqView=$qqView profile=${profile != null} displayName='$displayName' uin='$uin' uid='$uid'")

            // Long-press to copy the FULL value (TextView ellipsizes only at draw time, so the source
            // string is copied intact even when the on-screen text is truncated).
            nickView?.apply {
                isLongClickable = true
                setOnLongClickListener {
                    Utils.log("ProfileCardIconFix: nick long-press -> copy '$displayName'")
                    Utils.copyToClipboard(ctx, displayName, "已复制昵称"); true
                }
            }
            qqView?.apply {
                // The layout marks self_qq textIsSelectable, so a long-press starts the native text
                // selection toolbar and never reaches our listener — disable it so copy wins.
                setTextIsSelectable(false)
                isLongClickable = true
                setOnLongClickListener {
                    Utils.log("ProfileCardIconFix: qq long-press -> copy '$uin'")
                    Utils.copyToClipboard(ctx, uin, "已复制账号"); true
                }
            }

            // The displayed name is the group card (群名片) when opened from a group; surface the real
            // QQ nickname too. The card opener pre-fetches the profile, so the cache is already
            // populated here — a single synchronous read suffices (no polling).
            if (nickView != null && !uid.isNullOrEmpty()) {
                val color = qqView?.currentTextColor ?: M3.onSurface
                tryShowRealNick(nickView, uid, displayName, ctx, color)
                insertStatusLine(nickView, uid, ctx)
            } else {
                Utils.log("ProfileCardIconFix: skip real-nick (nickView=$nickView uid='$uid')")
            }
        } catch (e: Exception) {
            Utils.log("ProfileCardIconFix enrich error: ${e.message}")
        }
    }

    /** Insert the online-presence line under the nick (legacy, non-Material profile). Idempotent. */
    private fun insertStatusLine(nickView: View, uid: String, ctx: Context) {
        val statusTv = momoi.mod.qqpro.hook.action.profileOnlineStatusView(ctx, uid) ?: return
        val wrapper = nickView.parent as? View ?: return
        val column = wrapper.parent as? LinearLayout ?: return
        if (column.findViewWithTag<View>(STATUS_TAG) != null) return
        statusTv.tag = STATUS_TAG
        (statusTv.layoutParams as? LinearLayout.LayoutParams
            ?: LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )).also { it.gravity = Gravity.CENTER_HORIZONTAL; statusTv.layoutParams = it }
        column.addView(statusTv, column.indexOfChild(wrapper) + 1)
    }

    /** Insert the real-nickname line if it's available, differs from the shown name, and isn't shown yet. */
    private fun tryShowRealNick(nickView: View, uid: String, displayName: String, ctx: Context, color: Int) {
        val raw = fetchRealNick(uid)
        Utils.log("ProfileCardIconFix.realNick: raw='$raw' displayName='$displayName'")
        val realNick = raw?.takeIf { it.isNotEmpty() && it != displayName } ?: return
        val wrapper = nickView.parent as? View ?: return
        val column = wrapper.parent as? LinearLayout ?: return
        if (column.findViewWithTag<View>(REAL_NICK_TAG) != null) return // already inserted
        Utils.log("ProfileCardIconFix.realNick: inserting line '$realNick'")
        val line = TextView(ctx).apply {
            this.tag = REAL_NICK_TAG
            text = realNick
            textSize = 10f
            gravity = Gravity.CENTER
            alpha = 0.6f
            setTextColor(color)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            )
            isLongClickable = true
            setOnLongClickListener { Utils.copyToClipboard(ctx, realNick, "已复制昵称"); true }
        }
        column.addView(line, column.indexOfChild(wrapper) + 1)
    }

    /** Look up the contact's real QQ nickname from the profile cache by uid; null when unavailable. */
    private fun fetchRealNick(uid: String): String? = runCatching {
        val app = MobileQQ.sMobileQQ?.peekAppRuntime()
        val ks = app?.getRuntimeService(IKernelService::class.java, "") as? IKernelService
        val ps = ks?.profileService
        val map = ps?.getCoreAndBaseInfo("ProfileCardIconFix", arrayListOf(uid))
        val info = map?.get(uid)
        Utils.log("ProfileCardIconFix.fetchRealNick: app=${app != null} ks=${ks != null} ps=${ps != null} mapKeys=${map?.keys} info=${info != null} coreInfo=${info?.coreInfo != null}")
        info?.coreInfo?.nick
    }.onFailure { Utils.log("ProfileCardIconFix.fetchRealNick error: $it") }.getOrNull()

    companion object {
        private const val REAL_NICK_TAG = "qqpro_real_nick"
        private const val STATUS_TAG = "qqpro_online_status"
    }
}
