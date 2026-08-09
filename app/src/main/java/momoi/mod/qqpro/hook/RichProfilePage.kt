package momoi.mod.qqpro.hook
import momoi.mod.qqpro.lib.setElevationCompat

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.tencent.mobileqq.text.QQText
import momoi.mod.qqpro.lib.dp
import android.widget.ImageView
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3Button
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.lib.material.leadingSymbol
import momoi.mod.qqpro.lib.dpf
import momoi.mod.qqpro.util.Utils

/**
 * Fully rebuilds the profile-card page ([com.tencent.qqnt.watch.profile.ui.ProfileCardFragment]) into
 * a Material-style layout, gated by [momoi.mod.qqpro.Settings.useRichProfile].
 *
 * Instead of inflating fresh widgets, it **re-parents** the fragment's already-wired native views
 * (avatar with its async image load, the nickname QQText, the QQ-number line, and the 艾特/去聊天/加好友
 * buttons with their click handlers) into a brand-new view tree. That preserves all native behaviour
 * while giving us full control over layout (fixing the scroll issue) and styling, plus an extended-info
 * card fetched via [ProfileDetailCard].
 *
 * Top-level object (public) so the @Mixin hook in another package can call it; no anonymous classes are
 * declared inside the @Mixin body (see [ProfileDetailCard] doc).
 */
object RichProfilePage {
    private const val TAG = "qqpro_rich_profile"

    /**
     * Set true when the 艾特Ta button stages an @-mention and pops the profile. The chat's
     * WatchAIOFragment.onResume (see [WatchAIOPageReset]) consumes it by firing openIME, which the
     * inline route turns into an inline @ insert — the native handler's immediate openIME is lost
     * because the chat isn't resumed yet when the profile is still on top.
     */
    @JvmField var pendingAt = false

    /**
     * [root] is the fragment's content view (a ConstraintLayout). [uid]/[displayName]/[uin] come from
     * the args. [isFriend] drives the primary button (去聊天 vs 加好友) — read from the real friend
     * status, not the native button label. [fromGroup] gates the 艾特Ta button, which only makes
     * sense when the card was opened from a group (so the @ can be inserted into that group's input).
     */
    fun build(
        root: View, ctx: Context, uid: String, displayName: String, uin: String,
        isFriend: Boolean, fromGroup: Boolean, atAction: () -> Unit,
    ) {
        if (root !is ViewGroup || root.findViewWithTag<View>(TAG) != null) return
        runCatching {
            val pkg = ctx.packageName
            fun vid(name: String) = ctx.resources.getIdentifier(name, "id", pkg)
            val avatar = root.findViewById<View>(vid("avatar"))
            val selfQq = root.findViewById<View>(vid("self_qq"))
            val gotoChat = root.findViewById<View>(vid("goto_chat"))
            Utils.log("RichProfilePage.build uid=$uid friend=$isFriend fromGroup=$fromGroup avatar=${avatar != null} qq=${selfQq != null} goto=${gotoChat != null}")

            // Detach the original ScrollView so we can install our own scroll container.
            root.removeAllViews()
            // Match the app's dark theme (the native page uses a light blue→white gradient).
            root.setBackgroundColor(M3.surface)

            val content = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(14.dp, 30.dp, 14.dp, 34.dp)
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }

            // --- Header card: avatar + name + qq + extended info rows ---
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                background = GradientDrawable().apply {
                    cornerRadius = 18.dpf
                    setColor(M3.surfaceContainer)
                }
                setElevationCompat(3.dpf)
                setPadding(16.dp, 18.dp, 16.dp, 14.dp)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            }

            avatar?.let {
                reparent(it)
                it.layoutParams = LinearLayout.LayoutParams(64.dp, 64.dp).apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                }
                card.addView(it)
            }
            // Primary name — our own multiline TextView (the native one is single-line). Uses QQText so
            // QQ sysface emoji in the name still render. This is the displayed name (= group card name
            // 群名片 in a group context).
            val nameView = TextView(ctx).apply {
                text = runCatching { QQText(displayName, 19, 13, null) as CharSequence }.getOrDefault(displayName)
                textSize = 15f
                setTextColor(M3.onSurface)
                gravity = Gravity.CENTER
                maxLines = 4
                ellipsize = TextUtils.TruncateAt.END
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 10.dp }
                isLongClickable = true
                setOnLongClickListener { Utils.copyToClipboard(ctx, displayName, "已复制昵称"); true }
            }
            card.addView(nameView)

            // Secondary line: the real QQ nickname, shown only when it differs from the displayed name
            // (i.e. when the displayed name is a group card). Filled from the async fetch below.
            val realNickView = TextView(ctx).apply {
                textSize = 11f
                setTextColor(M3.onSurfaceVariant)
                gravity = Gravity.CENTER
                maxLines = 3
                ellipsize = TextUtils.TruncateAt.END
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 2.dp }
                isLongClickable = true
            }
            card.addView(realNickView)
            selfQq?.let {
                reparent(it)
                (it as? TextView)?.setTextIsSelectable(false)
                it.layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = 3.dp }
                setTextColorCompat(it, M3.onSurfaceVariant)
                it.isLongClickable = true
                it.setOnLongClickListener { Utils.copyToClipboard(ctx, uin, "已复制账号"); true }
                card.addView(it)
            }

            // Online presence line (friend or group member), gated by the profile status setting.
            momoi.mod.qqpro.hook.action.profileOnlineStatusView(ctx, uid)?.let {
                (it.layoutParams as? ViewGroup.MarginLayoutParams)?.topMargin = 4.dp
                card.addView(it)
            }

            // Extended-info rows (filled async). Hidden until data arrives.
            val rows = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = 6.dp }
            }
            val divider = View(ctx).apply {
                setBackgroundColor(M3.outlineVariant)
                visibility = View.GONE
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                    .apply { topMargin = 8.dp }
            }
            card.addView(divider)
            card.addView(rows)
            content.addView(card)

            if (uid.isNotEmpty()) {
                ProfileDetailCard.fetch(uid) { info ->
                    rows.post {
                        if (info == null) return@post
                        // Real nickname line (group card vs real nick).
                        val real = info.coreNick.trim()
                        if (real.isNotEmpty() && real != displayName.trim()) {
                            realNickView.text = runCatching { QQText(real, 19, 11, null) as CharSequence }.getOrDefault(real)
                            realNickView.setOnLongClickListener { Utils.copyToClipboard(ctx, real, "已复制昵称"); true }
                            realNickView.visibility = View.VISIBLE
                        }
                        if (ProfileDetailCard.bindInto(ctx, rows, info) > 0) {
                            divider.visibility = View.VISIBLE
                            rows.visibility = View.VISIBLE
                        }
                    }
                }
            }

            // --- Action buttons ---
            // All buttons use our own M3Button (the watch theme isn't MaterialComponents-based, so a
            // real MaterialButton can't be styled consistently — different font/metrics). We build a
            // fresh M3Button per action and forward its click to the native button's handler (or our
            // own action), instead of re-parenting the native MaterialButton. This keeps the buttons
            // visually identical.
            val H = 40.dp
            fun addM3Button(symbol: String, label: String, onClick: () -> Unit) {
                val mb = M3Button(ctx).variant(M3Button.Variant.FILLED).apply {
                    text = label
                    leadingSymbol(symbol, M3.onPrimary, sizeDp = 18)
                    setOnClickListener { onClick() }
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, H,
                    ).apply { topMargin = 10.dp }
                }
                content.addView(mb)
            }

            // Primary action — 去聊天 for friends, 加好友 for strangers. Driven by the real friend
            // status (the native goto_chat label can be wrong when the card is opened uid-only, e.g.
            // from a grey-tip name or a QZone header). The native goto_chat button carries the matching
            // handler (b0 → navigate to chat / addFriend coroutine), wired in super.onViewCreated from
            // the same ProfileData.isFriend; forwarding performClick() keeps that behaviour. The native
            // view stays off-screen after removeAllViews but its click listener still fires.
            gotoChat?.let { native ->
                if (isFriend) addM3Button(MaterialSymbols.chat_bubble, "去聊天") { native.performClick() }
                else addM3Button(MaterialSymbols.person_add, "加好友") { native.performClick() }
            }

            // 艾特Ta — only when opened from a group (the @ is staged then inserted into that group's
            // input bar via the chat's onResume). Hidden in DMs, add-friend/search, QZone, etc., where
            // there is no group to @ into. The native handler's immediate openIME is lost from the
            // profile page, so we always use our own stage-and-pop atAction.
            if (fromGroup) {
                addM3Button(MaterialSymbols.alternate_email, "艾特Ta") { atAction() }
            }

            // TA的空间 — always available: open the user's QZone home feed (reuses the chat-settings
            // QZone shortcut). Only the uin is needed, which every profile carries.
            val uinLong = uin.trim().toLongOrNull()
            if (uinLong != null && uinLong > 0) {
                addM3Button(MaterialSymbols.star, "TA的空间") { openUserQzone(content, uinLong) }
            }

            val scroll = ScrollView(ctx).apply {
                tag = TAG
                isFillViewport = false
                overScrollMode = View.OVER_SCROLL_NEVER
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
                )
                addView(content)
            }
            root.addView(scroll)
        }.onFailure { Utils.log("RichProfilePage.build error: $it") }
    }

    private fun reparent(v: View) {
        (v.parent as? ViewGroup)?.removeView(v)
    }

    /** A full-width pill (rounded-end) button background in the app's blue. */
    private fun pill(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 20.dpf
        setColor(M3.primary)
    }

    /** Set text color on a TextView, or on a custom widget (e.g. SingleLineTextView) via reflection. */
    private fun setTextColorCompat(v: View, color: Int) {
        if (v is TextView) { v.setTextColor(color); return }
        runCatching {
            v.javaClass.getMethod("setTextColor", Int::class.javaPrimitiveType).invoke(v, color)
        }
    }
}
