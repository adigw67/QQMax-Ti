package momoi.mod.qqpro.hook

import android.os.Build
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.tencent.qqnt.kernel.nativeinterface.DeleteRecentContactInfo
import com.tencent.qqnt.kernel.nativeinterface.IKernelRecentContactListener
import com.tencent.qqnt.kernel.nativeinterface.NotificationCommonInfo
import com.tencent.qqnt.kernel.nativeinterface.RecentContactExtra
import com.tencent.qqnt.kernel.nativeinterface.RecentContactInfo
import com.tencent.qqnt.kernel.nativeinterface.RecentContactListChangedInfo
import com.tencent.qqnt.msg.KernelServiceUtil
import com.tencent.qqnt.watch.ui.componet.tablayout.CircleIndicator
import com.tencent.watch.aio_impl.ui.WatchAIOFragment
import java.util.concurrent.atomic.AtomicBoolean
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.drawable.roundCornerDrawable
import momoi.mod.qqpro.enums.ChatType
import momoi.mod.qqpro.findAll
import momoi.mod.qqpro.hook.action.GroupMemberCache
import momoi.mod.qqpro.hook.action.RecentContacts
import momoi.mod.qqpro.renderQQFaces
import momoi.mod.qqpro.lib.FILL
import momoi.mod.qqpro.lib.WRAP
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.util.Utils

/**
 * Builds the chat titlebar (opt-in via [Settings.enableTitlebar]): a single full-width line
 * showing the group/contact name and, for groups, the member count — e.g. "群名 (233)".
 * A red unread badge (sum of other chats' unread) is overlaid at the top-left. Replaces the
 * top page-indicator strip of [WatchAIOFragment].
 */
object RichTitlebar {
    // Marks the titlebar bar so a rebuild can find and remove the previous one (idempotency).
    private const val BAR_TAG = "qqpro_rich_titlebar"
    // Marks the standalone chat-overlay unread badge (floatUnreadInChat option).
    private const val FLOAT_TAG = "qqpro_unread_float"

    // Current bar's badge + chat, plus the live unread map, updated by the kernel listener.
    private var badge: TextView? = null
    // The standalone unread badge floating over the chat (floatUnreadInChat); separate from the header.
    private var floatBadge: TextView? = null
    // Header row layout pieces, used by [relayoutTitle] to distribute slack between the two spacers:
    // [badge][space1][name][count][space2]. The name stays centered until the badge crowds it.
    private var nameView: TextView? = null
    private var countView: TextView? = null
    private var space1: View? = null
    private var space2: View? = null
    private var cornerPx: Int = 0
    private var screenWPx: Int = 0
    private var peer: String = ""
    // ConcurrentHashMap: the kernel UnreadListener mutates this on a binder thread while applyUnread
    // iterates it on the UI thread — a plain HashMap there can throw ConcurrentModificationException
    // (see the same fix in MainNav.unread).
    private val unread = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val listenerRegistered = AtomicBoolean(false)

    fun build(fragment: androidx.fragment.app.Fragment, root: ViewGroup, baseInset: Int = 0) {
        runCatching {
            val ctx = root.context

            // Idempotency: onViewCreated can fire again when returning from the image viewer /
            // avatar preview (same or recreated view tree). Drop any titlebar we built earlier so
            // we don't stack a second bar (doubled gradient + badge) on top of the first.
            while (true) {
                val old = root.findAll { it.tag == BAR_TAG } ?: break
                (old.parent as? ViewGroup)?.removeView(old) ?: break
            }
            val args = fragment.arguments
            val chatType = args?.getInt("key_bundle_chat_type") ?: 0
            val isGroup = chatType == ChatType.GROUP
            val peerId = args?.getString("key_bundle_peer_id").orEmpty()
            val nick = args?.getString("key_bundle_chat_nick")?.takeIf { it.isNotEmpty() }
            val rc = RecentContacts.get(peerId)
            val name = nick
                ?: rc?.raw?.remark?.takeIf { it.isNotEmpty() }
                ?: rc?.raw?.peerName?.takeIf { it.isNotEmpty() }
                ?: peerId

            Utils.log("RichTitlebar build: name=$name peer=$peerId type=$chatType")
            if (name.isEmpty()) {
                Utils.log("RichTitlebar: blank name, skip")
                return
            }

            // Remove the page-indicator dots — the titlebar shows only the name + member count.
            val indicator = root.findAll { it is CircleIndicator }
            (indicator?.parent as? ViewGroup)?.removeView(indicator)

            val heightPx = Settings.titlebarHeight.value.toInt().dp
            val corner = Settings.titlebarSideMargin.value.toInt().dp
            val screenW = ctx.resources.displayMetrics.widthPixels
            // Match the input pill exactly: it sits at (chat content base inset) + (corner margin).
            val sideMargin = baseInset + corner
            cornerPx = sideMargin
            screenWPx = screenW

            // Row laid out as [badge][space1][name][count][space2], with the same side margin as the
            // input pill (corner diameter). The two spacers share the slack so the name is centered
            // until the badge would crowd it, then it sits just right of the badge — see relayoutTitle.
            val badgeView = TextView(ctx).apply {
                setTextColor(M3.onSurface)
                textSize = 10f
                gravity = Gravity.CENTER
                background = roundCornerDrawable(M3.error, 9999f)
                setPadding(4.dp, 0, 4.dp, 0)
                visibility = View.GONE
            }
            val nameTv = TextView(ctx).apply {
                setTextColor(M3.onSurface)
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                isSingleLine = true
                if (Settings.titlebarMarquee.value) {
                    // Scroll the over-long name instead of clamping it with an ellipsis. Marquee
                    // needs the view "selected" to animate without focus, and a forever repeat.
                    ellipsize = TextUtils.TruncateAt.MARQUEE
                    marqueeRepeatLimit = -1
                    isSelected = true
                } else {
                    ellipsize = TextUtils.TruncateAt.END
                }
                // Render QQ sysface codes in the name into face glyphs (a plain TextView shows the
                // raw codes as □ boxes), sized to the 15sp title.
                text = FontPack.fallback(renderQQFaces(name, 15))
            }
            val countTv = TextView(ctx).apply {
                setTextColor(M3.onSurface)
                textSize = 15f
                typeface = android.graphics.Typeface.DEFAULT_BOLD
                isSingleLine = true
                text = ""
            }
            val sp1 = View(ctx)
            val sp2 = View(ctx)
            nameView = nameTv
            countView = countTv
            space1 = sp1
            space2 = sp2

            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            row.addView(badgeView, LinearLayout.LayoutParams(WRAP, WRAP).apply {
                gravity = Gravity.CENTER_VERTICAL
            })
            row.addView(sp1, LinearLayout.LayoutParams(0, 1))
            row.addView(nameTv, LinearLayout.LayoutParams(WRAP, WRAP))
            row.addView(countTv, LinearLayout.LayoutParams(WRAP, WRAP))
            row.addView(sp2, LinearLayout.LayoutParams(0, 1))

            if (isGroup) {
                runCatching {
                    // Cached list lands immediately so the count shows at once; the server fetch only
                    // updates it when the member count actually changed.
                    GroupMemberCache.load(peerId.toLong()) { members ->
                        countTv.post { countTv.text = " (${members.size})"; relayoutTitle() }
                    }
                }.onFailure { Utils.log("RichTitlebar member fetch failed: $it") }
            } else if (Settings.onlineStatusTitlebar.value && peerId.isNotEmpty()) {
                // DM: show the peer's presence (e.g. "手机在线") in the otherwise-empty count slot.
                applyDmOnlineStatus(countTv, peerId)
            }

            val bar = FrameLayout(ctx)
            bar.tag = BAR_TAG
            // In the chat-only overlay mode the bar is a sibling of the ChatFragment view, which is
            // committed asynchronously *after* this runs and would otherwise draw on top. Lift the bar
            // with elevation/translationZ so it stays above the chat list regardless of add order.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                bar.elevation = 12.dp.toFloat()
                bar.translationZ = 12.dp.toFloat()
            } else {
                // No elevation on this API 19 watch — pull the bar in front explicitly after attach.
                bar.post { bar.bringToFront() }
            }
            // 纯色 M3 surface 背景：底部已有 1dp 分隔线（边缘线），不再用渐变淡出。
            bar.background = android.graphics.drawable.ColorDrawable(M3.surface)
            // MD3 顶栏：底部一条 1dp outlineVariant 分隔线，把标题栏与消息区区分开。
            bar.addView(View(ctx).apply { setBackgroundColor(M3.outlineVariant) },
                FrameLayout.LayoutParams(FILL, 1.dp, Gravity.BOTTOM))
            // Same horizontal inset as the input pill (chat base inset + corner) on both sides.
            bar.addView(row, FrameLayout.LayoutParams(FILL, FILL).apply {
                leftMargin = sideMargin; rightMargin = sideMargin
            })

            // Tapping the badge exits the current chat.
            badgeView.setOnClickListener {
                runCatching { fragment.requireActivity().onBackPressed() }
                    .onFailure { Utils.log("RichTitlebar badge back failed: $it") }
            }

            // Track this bar for live unread updates; seed from the cached conversation list.
            // Don't clear `unread`: it's a singleton the kernel listener keeps live across rebuilds.
            // On a rebuild (returning from the image/avatar viewer) the recent-contact list isn't
            // re-rendered, so RecentContacts.map is stale/empty — clearing would blank the badge.
            // Seed only keys we don't already have, preserving the live values.
            badge = badgeView
            peer = peerId
            RecentContacts.map.forEach { (k, v) -> if (!unread.containsKey(k)) unread[k] = v.unreadCntCached }
            badgeView.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) { if (badge === badgeView) badge = null }
            })
            ensureListener()
            applyUnread()

            root.addView(bar, FrameLayout.LayoutParams(FILL, heightPx))
            // Re-assert on top after the async ChatFragment view attaches (chat-only overlay).
            bar.bringToFront()
            root.post { bar.bringToFront() }
            // Keyboard handling is done purely via the window soft-input mode (adjustResize when the
            // bar should stay showing, native adjustPan when it should slide off) — no IME listener.
            attachWindowMode(bar)
            Utils.log("RichTitlebar: built (name=$name group=$isGroup)")
        }.onFailure { Utils.log("RichTitlebar.build failed: $it") }
    }

    // The chat window's native soft-input mode (adjustPan), saved when we force adjustResize so it can
    // be restored on chat exit. null = we haven't overridden it (nothing to restore).
    private var savedSoftInputMode: Int? = null

    /** Unwrap the hosting [android.app.Activity] from a view's context chain (null if none). */
    private fun activityOf(v: View): android.app.Activity? {
        var c: android.content.Context? = v.context
        while (c is android.content.ContextWrapper) {
            if (c is android.app.Activity) return c
            c = c.baseContext
        }
        return null
    }

    /**
     * Force the chat window to SOFT_INPUT_ADJUST_RESIZE. Root fix for the titlebar: natively the chat
     * uses adjustPan, so opening the keyboard scrolls the WHOLE window up and shoves the top-anchored
     * titlebar off the screen — which the old code fought with fragile translationY pinning (bar
     * slides off anyway / doesn't reset on collapse). With adjustRESIZE the window shrinks instead, so
     * the top bar stays put and the content (list + floating input) reflows above the keyboard. Only
     * the ADJUST bits are changed; the STATE bits are preserved. The native mode is saved once so
     * [restoreSoftInput] can put it back when the chat closes.
     */
    private fun forceResizeSoftInput(v: View) {
        runCatching {
            val win = activityOf(v)?.window ?: return
            val cur = win.attributes.softInputMode
            val adjust = cur and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST
            // Never remember a resize mode as the "native" one (double-apply across preload/re-attach).
            if (adjust != WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) savedSoftInputMode = cur
            if (adjust != WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) {
                val next = (cur and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST.inv()) or
                    WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                win.setSoftInputMode(next)
                Utils.log("RichTitlebar: softInputMode -> ADJUST_RESIZE (was mode=$cur)")
            }
        }.onFailure { Utils.log("RichTitlebar.forceResizeSoftInput failed: $it") }
    }

    /** Restore the window's native soft-input mode captured by [forceResizeSoftInput]. */
    private fun restoreSoftInput(v: View) {
        val saved = savedSoftInputMode ?: return
        runCatching {
            activityOf(v)?.window?.setSoftInputMode(saved)
            Utils.log("RichTitlebar: softInputMode restored (mode=$saved)")
        }.onFailure { Utils.log("RichTitlebar.restoreSoftInput failed: $it") }
        savedSoftInputMode = null
    }

    /**
     * Set the chat window's soft-input mode based on [Settings.hideTitlebarWhenTyping], applied while
     * the titlebar is attached and restored on detach (chat exit). The window mode IS the whole
     * mechanism now — the bar's visibility is never toggled and there is no IME listener:
     *  - hide-while-typing OFF: force ADJUST_RESIZE → the keyboard shrinks the content and the
     *    top-anchored bar stays put (the reliable "keep showing" behaviour).
     *  - hide-while-typing ON: leave the native adjustPan → opening the keyboard pans the whole window
     *    (and the top bar) up off the screen, so the bar naturally disappears while typing and slides
     *    back when the keyboard closes. No detection, no translationY, nothing to reset.
     * This is version-independent (no API-30 IME-inset dependency), so it works on Android 10 too.
     */
    private fun attachWindowMode(bar: View) {
        bar.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) = applyWindowMode(v)
            override fun onViewDetachedFromWindow(v: View) = restoreSoftInput(v)
        })
        if (bar.isAttachedToWindow) applyWindowMode(bar)
    }

    private fun applyWindowMode(v: View) {
        // Only override when "keep showing" is wanted; hide-while-typing keeps the native adjustPan.
        if (!Settings.hideTitlebarWhenTyping.value) forceResizeSoftInput(v)
    }

    /**
     * Builds a standalone unread badge floating over the chat's top-left corner (the
     * [Settings.floatUnreadInChat] option), independent of the titlebar. Same red badge / live
     * updates as the header one, but overlaid on the chat content instead of the header strip.
     */
    fun buildFloating(fragment: WatchAIOFragment, root: ViewGroup) {
        runCatching {
            val ctx = root.context

            // Idempotency: drop any float badge we built earlier (onViewCreated can fire again).
            while (true) {
                val old = root.findAll { it.tag == FLOAT_TAG } ?: break
                (old.parent as? ViewGroup)?.removeView(old) ?: break
            }

            val peerId = fragment.arguments?.getString("key_bundle_peer_id").orEmpty()
            val corner = Settings.titlebarSideMargin.value.toInt().dp

            val view = TextView(ctx).apply {
                tag = FLOAT_TAG
                setTextColor(M3.onSurface)
                textSize = 10f
                gravity = Gravity.CENTER
                background = roundCornerDrawable(M3.error, 9999f)
                setPadding(5.dp, 1.dp, 5.dp, 1.dp)
                visibility = View.GONE
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    elevation = 10.dp.toFloat()
                    translationZ = 10.dp.toFloat()
                }
            }
            // Tapping the badge exits the current chat (same as the header badge).
            view.setOnClickListener {
                runCatching { fragment.requireActivity().onBackPressed() }
                    .onFailure { Utils.log("RichTitlebar float badge back failed: $it") }
            }
            root.addView(view, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.START or Gravity.TOP).apply {
                leftMargin = maxOf(corner, 4.dp)
                topMargin = maxOf(corner, 4.dp)
            })

            floatBadge = view
            peer = peerId
            RecentContacts.map.forEach { (k, v) -> if (!unread.containsKey(k)) unread[k] = v.unreadCntCached }
            view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {}
                override fun onViewDetachedFromWindow(v: View) { if (floatBadge === view) floatBadge = null }
            })
            ensureListener()
            applyUnread()
            Utils.log("RichTitlebar: float badge built (peer=$peerId)")
        }.onFailure { Utils.log("RichTitlebar.buildFloating failed: $it") }
    }

    private fun applyUnread() {
        // Exclude the current chat and any 免打扰 (DND) chats from the badge total.
        if (Utils.loggingEnabled) {
            unread.entries.filter { it.value > 0 && it.key != peer }.forEach {
                Utils.log("RichTitlebar unread breakdown: peer=${it.key} cnt=${it.value} disturb=${RecentContacts.isDisturb(it.key)}")
            }
        }
        val other = unread.entries
            .filter { it.key != peer && !RecentContacts.isDisturb(it.key) }
            .sumOf { it.value }
        val text = if (other > 99) "99+" else other.toString()
        // Float option takes over the unread display: when on, hide the header badge and show the
        // floating one; otherwise the header badge follows titlebarShowUnread.
        val floatOn = Settings.floatUnreadInChat.value
        val showHeader = !floatOn && Settings.titlebarShowUnread.value && other > 0
        val showFloat = floatOn && other > 0

        badge?.let { b ->
            b.post {
                b.text = text
                b.visibility = if (showHeader) View.VISIBLE else View.GONE
                relayoutTitle()
            }
        }
        floatBadge?.let { f ->
            f.post {
                if (showFloat) {
                    f.text = text
                    f.visibility = View.VISIBLE
                    f.bringToFront()
                } else {
                    f.visibility = View.GONE
                }
            }
        }
    }

    /**
     * Distributes the row's slack between [space1] and [space2] over [badge][space1][name][count]
     * [space2]: pour everything into space2 first until it equals the badge width, then split the
     * rest evenly. Net effect — the name+count block is centered on screen while there's room, and
     * once the badge would crowd it, the block stays just to the right of the badge (and the name
     * ellipsizes if even that won't fit). Width is the fixed row width: screen − 2·corner margin.
     */
    /**
     * DM titlebar presence: render the peer's status (e.g. "手机在线") in the count slot, coloured by
     * online state and sized down. Starts polling, primes this peer, and observes for live updates;
     * the observer detaches with the count view so it doesn't leak across chats.
     */
    private fun applyDmOnlineStatus(countTv: TextView, peerId: String) {
        momoi.mod.qqpro.hook.action.OnlineStatus.start()
        momoi.mod.qqpro.hook.action.OnlineStatus.prime(listOf(peerId))
        countTv.textSize = 10f
        val update = {
            val desc = momoi.mod.qqpro.hook.action.OnlineStatus.describe(peerId)
            if (desc != null) {
                countTv.setTextColor(
                    momoi.mod.qqpro.hook.action.OnlineStatusUi.color(
                        momoi.mod.qqpro.hook.action.OnlineStatus.isOnline(peerId)
                    )
                )
                countTv.text = "  $desc"
            } else {
                countTv.text = ""
            }
            relayoutTitle()
        }
        update()
        val observer: () -> Unit = { countTv.post { update() }; Unit }
        momoi.mod.qqpro.hook.action.OnlineStatus.addObserver(observer)
        countTv.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) {}
            override fun onViewDetachedFromWindow(v: View) {
                momoi.mod.qqpro.hook.action.OnlineStatus.removeObserver(observer)
            }
        })
    }

    private fun relayoutTitle() {
        val name = nameView ?: return
        val count = countView ?: return
        val b = badge ?: return
        val s1 = space1 ?: return
        val s2 = space2 ?: return
        val total = screenWPx - 2 * cornerPx
        if (total <= 0) return

        fun measure(v: View): Int {
            v.measure(
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            return v.measuredWidth
        }

        val badgeW = if (b.visibility == View.VISIBLE) measure(b) else 0
        val countW = measure(count)
        // Natural name width (unclamped), then clamp so badge + name + count fit the row.
        name.maxWidth = Int.MAX_VALUE
        val nameNat = measure(name)
        val nameMax = (total - badgeW - countW).coerceAtLeast(20.dp)
        if (nameNat > nameMax) name.maxWidth = nameMax
        val titleW = minOf(nameNat, nameMax) + countW

        val extra = (total - badgeW - titleW).coerceAtLeast(0)
        val s1w: Int
        val s2w: Int
        if (extra <= badgeW) {
            s1w = 0; s2w = extra
        } else {
            val half = (extra - badgeW) / 2
            s1w = half; s2w = badgeW + half
        }
        s1.layoutParams = s1.layoutParams.apply { width = s1w }
        s2.layoutParams = s2.layoutParams.apply { width = s2w }
        (s1.parent as? View)?.requestLayout()
    }

    private fun ensureListener() {
        if (!listenerRegistered.compareAndSet(false, true)) return
        runCatching {
            KernelServiceUtil.g()?.recentContactService?.addKernelRecentContactListener(UnreadListener())
            Utils.log("RichTitlebar: unread listener registered")
        }.onFailure { listenerRegistered.set(false); Utils.log("RichTitlebar listener reg failed: $it") }
    }

    private fun put(c: RecentContactInfo?) {
        c ?: return
        val uid = c.peerUid ?: return
        if (uid.isEmpty()) return
        unread[uid] = c.unreadCnt.toInt()
        // Capture the kernel's muted/DND state so DND chats are excluded even before their row renders.
        RecentContacts.recordMuted(uid, RecentContacts.isMuted(c.isMsgDisturb, c.shieldFlag))
    }

    /** Live unread updates pushed by the kernel; we recompute and refresh the badge in place. */
    private class UnreadListener : IKernelRecentContactListener {
        override fun onMsgUnreadCountUpdate(map: HashMap<String, Int>?) {
            if (map != null) { unread.putAll(map); applyUnread() }
        }
        override fun onRecentContactListChanged(
            sorted: ArrayList<Long>?, changed: ArrayList<RecentContactInfo>?, extra: RecentContactExtra?,
        ) { changed?.forEach { put(it) }; applyUnread() }
        override fun onRecentContactListChangedVer2(list: ArrayList<RecentContactListChangedInfo>?, seq: Int) {
            list?.forEach { it.changedList?.forEach { c -> put(c) } }; applyUnread()
        }
        override fun onRecentContactNotification(
            list: ArrayList<RecentContactInfo>?, common: NotificationCommonInfo?, seq: Int,
        ) { list?.forEach { put(it) }; applyUnread() }
        override fun onGuildDisplayRecentContactListChanged(list: ArrayList<RecentContactListChangedInfo>?) {}
        override fun onDeletedContactsNotify(list: ArrayList<DeleteRecentContactInfo>?) {}
    }
}
