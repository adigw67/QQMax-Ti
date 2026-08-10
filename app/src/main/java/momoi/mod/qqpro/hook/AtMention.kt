package momoi.mod.qqpro.hook

import android.os.Bundle
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.method.LinkMovementMethod
import android.text.style.ClickableSpan
import android.text.TextPaint
import android.view.View
import android.widget.TextView
import com.tencent.qqnt.kernel.nativeinterface.MemberInfo
import com.tencent.qqnt.watch.contact.api.IContactRuntimeService
import com.tencent.qqnt.msg.KernelServiceUtil
import com.tencent.qqnt.watch.profile.ProfileData
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.hook.action.CurrentContact
import momoi.mod.qqpro.hook.action.CurrentGroupMembers
import momoi.mod.qqpro.hook.action.SelfContact
import momoi.mod.qqpro.hook.action.isGroup
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.linkColorResolved
import mqq.app.MobileQQ

// Default link color for tappable @member spans (also reused for clickable grey-tip member names
// in GrayTipMention.kt). A light cyan-blue: a medium blue (the old 5E97F6) blended into the blue
// self-bubble; this lighter tone stays legible on both the blue self-bubble and dark other-bubbles.
// Overridden by Settings.linkColor when set.
internal const val AT_LINK_COLOR = 0xFF_80D8FF.toInt()

/**
 * Open [member]'s profile card — the same destination reached by tapping their
 * avatar or name in a group chat. Replicates the app's
 * `ProfileRuntimeServiceImpl.startMemberProfileCard`: builds a [ProfileData] and
 * navigates to `profileCardFragment` via the (obfuscated) NavController resolved
 * from this view's tree (see [findNavControllerFromTree]).
 */
fun View.openMemberProfile(member: MemberInfo) {
    // ProfileData(birthday, gender, uin, uid, nickName, isFriend) — the card
    // re-fetches the full profile from the uid on open.
    navigateToProfile(ProfileData("0-0", -1, member.uin.toString(), member.uid, member.showName(), isFriend(member.uid)))
}

/**
 * Real friend status for a [uid] via the kernel contact service — the same source the app's own
 * navigation uses. The profile card's primary button (去聊天 vs 加好友) is driven by this; opening
 * with a hardcoded value made the card mis-detect friends as strangers when reached uid-only.
 */
private fun isFriend(uid: String): Boolean = try {
    if (uid.isEmpty()) false
    else {
        val app = MobileQQ.getMobileQQ().peekAppRuntime()
        (app?.getRuntimeService(IContactRuntimeService::class.java, "") as? IContactRuntimeService)
            ?.isFriend(uid) ?: false
    }
} catch (e: Exception) {
    false
}

/**
 * Open the profile card for a bare [uid] — used when only the uid is known (e.g. grey
 * tip names, whose uid is carried on the highlight span). If the uid belongs to a loaded
 * current-group member we reuse its richer info; otherwise the card re-fetches from the
 * uid alone.
 */
fun View.openMemberProfileByUid(uid: String) {
    if (uid.isEmpty()) return
    CurrentGroupMembers.info?.get(uid)?.let { return openMemberProfile(it) }
    // QQ's ProfileCardFragment.onViewCreated does Long.parseLong(profileData.uin) — opening with an
    // empty uin (uid-only, e.g. a grey-tip name) makes it crash with NumberFormatException. Resolve
    // the uin from the uid first; if it can't be resolved, skip rather than crash the app.
    val uin = uidToUin(uid)
    if (uin == null) {
        Utils.log("openMemberProfileByUid: no uin for uid=$uid; skip to avoid ProfileCard parseLong crash")
        Utils.toast(context, "无法打开资料卡")
        return
    }
    navigateToProfile(ProfileData("0-0", -1, uin, uid, "", isFriend(uid)))
}

/**
 * Resolve a member [uid] to its numeric uin string via the kernel uix-convert service (the same
 * call the app's own grey-tip decoder uses), or null if it can't be resolved.
 */
private fun uidToUin(uid: String): String? = try {
    KernelServiceUtil.f()?.uixConvertService?.y(uid)?.takeIf { it > 0L }?.toString()
} catch (e: Throwable) {
    Utils.log("uidToUin failed for $uid: ${e.message}")
    null
}

/**
 * Open the profile card for a bare [uin] — used by the QZone header (we only know the uin there).
 * Resolves the uid from the local contact cache when possible (friends); strangers open with the
 * uin only, which is enough for [com.tencent.qqnt.watch.profile.ui.ProfileCardFragment] to open
 * (it only crashes on an EMPTY uin via its `Long.parseLong`). The card re-fetches the rest by uid.
 */
fun View.openProfileByUin(uin: Long) {
    if (uin <= 0L) return
    val view = this
    // Resolve uid (local cache → server) FIRST: ProfileCardFragment fetches name/avatar BY UID, so
    // opening with an empty uid (the common stranger case) shows only the bare QQ number. Server
    // resolution runs async on a binder thread → re-post to the UI thread to navigate.
    ProfileDetailCard.resolveUid(uin) { uid ->
        view.post {
            Utils.log("openProfileByUin uin=$uin uid=${uid?.ifEmpty { null } ?: "(none)"}")
            // Birthday "" (not the "0-0" member marker) so the profile card never treats a QZone-opened
            // card as a group-member open: the 艾特Ta button can't redirect back into a chat from here,
            // so it must stay hidden. ProfileData.birthday is ignored by native code (the card re-fetches
            // detail by uid), so this only affects our own fromGroup test in ProfileCardIconFix.
            view.navigateToProfile(ProfileData("", -1, uin.toString(), uid.orEmpty(), "", isFriend(uid.orEmpty())))
        }
    }
}

/** Navigate to `profileCardFragment` with [profileData] via the obfuscated NavController. */
private fun View.navigateToProfile(profileData: ProfileData) {
    try {
        val nav = findNavControllerFromTree() ?: run {
            Utils.log("navigateToProfile: NavController not found in view tree")
            return
        }
        val destId = resources.getIdentifier("profileCardFragment", "id", context.packageName)
        if (destId == 0) {
            Utils.log("navigateToProfile: profileCardFragment id not found")
            return
        }
        val bundle = Bundle().apply { putParcelable("profile_data", profileData) }
        // navigate(int destId, Bundle args, NavOptions options) — name obfuscated.
        val navigate = nav.javaClass.methods.firstOrNull { m ->
            val p = m.parameterTypes
            p.size == 3 && p[0] == Int::class.javaPrimitiveType && p[1] == Bundle::class.java
        } ?: run {
            Utils.log("navigateToProfile: navigate(int,Bundle,..) not found on ${nav.javaClass.name}")
            return
        }
        navigate.invoke(nav, destId, bundle, null)
    } catch (e: Exception) {
        Utils.log("navigateToProfile error: ${e.message}")
    }
}

/** Display name shown for a member: card name, else remark, else nick, else uin. */
private fun MemberInfo.showName(): String =
    cardName.ifEmpty { remark.ifEmpty { nick.ifEmpty { uin.toString() } } }

/** Marker for the clickable spans this file adds for @mention usernames. Lets a re-linkify (when the
 *  member list arrives after linkify() already ran) tell its OWN mention spans — skip, so the pass
 *  stays idempotent — from URL/number spans added by [momoi.mod.qqpro.util.linkify], which are removed
 *  so the username always wins over a nickname that happens to look like a host/number. */
internal interface MentionSpan

/** A ClickableSpan that opens [member]'s profile card. Uses the user's link color override when
 *  set (so links/numbers/mentions share one color), else the Material accent. When [isSelf] is set
 *  (the mention targets you, and 高亮@我 is on) it's painted in the Material error color instead, so
 *  a mention of yourself stands out from ordinary mentions. */
private fun memberSpan(member: MemberInfo, isSelf: Boolean): ClickableSpan = object : ClickableSpan(), MentionSpan {
    override fun onClick(widget: View) = widget.openMemberProfile(member)
    override fun updateDrawState(ds: TextPaint) {
        // 与链接/号码统一用 linkColorResolved（用户设置的链接颜色，留空=M3 主题色）；
        // @自己仍用错误色高亮（设置开关控制）。
        ds.color = if (isSelf) M3.error else linkColorResolved()
        ds.isUnderlineText = false
    }
}

/** True when [member] is the logged-in user (its uid matches our own). */
private fun MemberInfo.isSelfMember(): Boolean =
    uid.isNotEmpty() && uid == SelfContact.peerUid

/** ClickableSpans truly overlapping [start,end) in [sp] (getSpans is loose at boundaries). */
private fun clickableSpansIn(sp: Spannable, start: Int, end: Int): List<ClickableSpan> =
    sp.getSpans(start, end, ClickableSpan::class.java).filter {
        sp.getSpanStart(it) < end && start < sp.getSpanEnd(it)
    }

/**
 * In a group chat, make `@member` mentions in this TextView tappable — tapping
 * opens the member's profile card. Matches the longest current-group member
 * display name (card name / remark / nick) that follows an `@`, so `@all` and
 * non-member text are left alone. No-op outside groups, when the setting is off,
 * or before the member list has loaded.
 */
/**
 * Content TextViews that ran [parseAtMembers] in a group chat. Tracked weakly so that
 * when the member list finishes loading (it arrives async, after the first screen of
 * messages has already been bound) we can re-linkify the already-rendered cells without
 * waiting for them to scroll-recycle. See [relinkifyAtMembers].
 */
internal val atContentViews: MutableSet<TextView> =
    java.util.Collections.newSetFromMap(java.util.WeakHashMap())

private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

/** Re-run [parseAtMembers] on every tracked content view — call when members load. */
fun relinkifyAtMembers() {
    mainHandler.post {
        // WeakHashMap iteration can throw NoSuchElementException when the GC clears a weak
        // key between the iterator's hasNext() and next(). The race is transient (the stale
        // entry is expunged on the next pass), so retry the snapshot a few times; if it still
        // won't settle, skip this round — relinkify is idempotent and runs again on the next
        // member update or cell recycle.
        val views = (0 until 3).firstNotNullOfOrNull {
            runCatching { atContentViews.toList() }.getOrNull()
        } ?: return@post
        views.forEach { it.parseAtMembers() }
    }
}

fun TextView.parseAtMembers() {
    if (!Settings.parseAtMember.value || !CurrentContact.isGroup) return
    val raw = text ?: return
    if (raw.indexOf('@') < 0) return
    // Remember the view so it can be re-linkified once the member list arrives (it loads
    // async — the first rendered cells would otherwise stay un-linked until recycled).
    atContentViews.add(this)
    val members = CurrentGroupMembers.info ?: return
    // Names longest-first so "@张三丰" wins over a member literally named "张三".
    val named = members.values
        .flatMap { m -> setOf(m.cardName, m.remark, m.nick).filter { it.isNotEmpty() }.map { it to m } }
        .sortedByDescending { it.first.length }
    if (named.isEmpty()) return

    val sp = raw as? Spannable ?: SpannableStringBuilder(raw)
    val s = sp.toString()
    var i = 0
    var added = false
    while (i < s.length) {
        if (s[i] == '@') {
            val rest = s.substring(i + 1)
            val match = named.firstOrNull { rest.startsWith(it.first) }
            if (match != null) {
                val end = i + 1 + match.first.length
                val overlapping = clickableSpansIn(sp, i, end)
                // Skip only if we've already linked this mention (idempotent re-linkify). Any other
                // clickable span here is a URL/number span from linkify() that ran first — remove it
                // so the username wins ("matched before anything else").
                if (overlapping.none { it is MentionSpan }) {
                    overlapping.forEach { sp.removeSpan(it) }
                    val isSelf = Settings.highlightSelfMention.value && match.second.isSelfMember()
                    sp.setSpan(memberSpan(match.second, isSelf), i, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                    added = true
                    i = end
                    continue
                }
            } else {
                // DIAG: log the bytes after an unmatched '@' to understand "no-space" cases.
                val tail = s.substring(i, minOf(s.length, i + 12))
                Utils.log("parseAtMembers: no match after @ -> [" +
                    tail.map { it.code.toString(16) }.joinToString(" ") + "] '" + tail + "'")
            }
        }
        i++
    }
    if (added) {
        text = sp
        movementMethod = LinkMovementMethod.getInstance()
    }
}
