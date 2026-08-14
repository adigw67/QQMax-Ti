package momoi.mod.qqpro.hook.action

import android.os.Bundle
import android.text.SpannableStringBuilder
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.graphics.drawable.Drawable
import android.widget.ImageView
import com.tencent.qqnt.chats.core.adapter.itemdata.RecentContactChatItem
import com.tencent.qqnt.kernel.nativeinterface.RecentContactInfo
import com.tencent.qqnt.watch.chat.list.WatchRecentContactHolder
import com.tencent.qqnt.watch.chat.list.WatchRecentItemBuilder
import com.tencent.qqnt.watch.chat.ui.ChatListFragment
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.hook.FontPack
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.util.Utils

/**
 * Material colors for the conversation/message list (1st page). Recolors each row's card background
 * and its title/time/preview text to M3 tokens. Gated by [Settings.materialChatList]. The native bind
 * only sets text content, so re-applying on every full bind keeps it correct across recycling.
 */
fun materializeChatRow(holder: WatchRecentContactHolder) {
    if (!Settings.materialChatList.value) return
    runCatching {
        val b = holder.b
        b.a.background = chatRowBackground() // row card（原型克隆，滚动零新建）
        b.c.setTextColor(M3.onSurface)         // title (name)
        b.d.setTextColor(M3.onSurfaceTip)      // time
        b.e.setTextColor(M3.onSurfaceVariant)  // preview / last message
    }.onFailure { Utils.log("materializeChatRow failed: $it") }
}

/** 会话行背景原型（按 颜色+圆角 缓存）：每行用 constantState.newDrawable() 轻量克隆。
 *  之前每行每次绑定都新建 GradientDrawable + StateListDrawable，滚动时是 GC 压力源。 */
private val rowBgPrototypes = HashMap<Long, Drawable>()

private fun chatRowBackground(): Drawable {
    val color = M3.surfaceContainer
    val radius = M3.radiusLg
    val key = (color.toLong() shl 32) or (radius.toRawBits().toLong() and 0xFFFFFFFFL)
    val proto = rowBgPrototypes.getOrPut(key) { M3.ripple(M3.rounded(color, radius)) }
    return proto.constantState?.newDrawable() ?: proto
}

/** 字体包启用时，对会话行标题/预览做逐字兜底（MiSans 未覆盖的生僻字用 Unifont）。 */
fun fontWrapChatRow(holder: WatchRecentContactHolder) {
    if (!FontPack.installed()) return
    runCatching {
        val b = holder.b
        b.c.text = FontPack.fallback(b.c.text) // 标题（群名/备注）
        b.e.text = FontPack.fallback(b.e.text) // 预览（最后一条消息）
    }.onFailure { Utils.log("fontWrapChatRow failed: $it") }
}

/** Material background for the conversation-list page (replaces the native bg_blue with M3.surface). */
@Mixin
class ChatListMaterial : ChatListFragment() {
    override fun Y(p0: LayoutInflater, p1: ViewGroup?, p2: Bundle?): View {
        val root = super.Y(p0, p1, p2)!!
        if (Settings.materialChatList.value) {
            root.setBackgroundColor(M3.surface)
            Utils.log("ChatListMaterial: list background materialized")
        }
        // 圆屏模式：会话列表根布局用**对称**的圆形内切矩形 padding 整体缩进（setPadding(p,p,p,p)），
        // 不做单向偏移，首尾行与行卡片对称落入内切正方形内。
        momoi.mod.qqpro.lib.RoundWatch.applyUniformInset(root)
        // Keep DM presence dots fresh as status pushes arrive (gated inside).
        wireRecentOnlineRefresh(root)
        return root
    }
}

/**
 * Replace the conversation row's native 置顶 (pin) glyph with the Material push_pin symbol, tinted to
 * the theme accent. The native bind only toggles the icon's visibility, so swapping the drawable on
 * full bind persists across show/hide. Gated by the M3 redesign toggle.
 */
/** Cached resource id of the native pin icon (getIdentifier is a slow package-wide lookup). */
private var topIconId = 0

fun swapPinIcon(holder: WatchRecentContactHolder) {
    if (!Settings.materialChatList.value) return
    runCatching {
        val root = holder.itemView
        val id = if (topIconId != 0) topIconId
        else root.resources.getIdentifier("top_icon", "id", root.context.packageName)
            .also { topIconId = it }
        val iv = if (id != 0) root.findViewById<ImageView>(id) else null
        // Only pinned rows show the icon; skip the (vector) drawable construction for the
        // overwhelming majority of rows so scrolling stays cheap.
        if (iv == null || iv.visibility != View.VISIBLE) return@runCatching
        // Filled accent disc with a white pin (same circled treatment used for M3 list icons) so the
        // tiny corner marker reads clearly against any avatar.
        iv?.setImageDrawable(MaterialSymbol.circled(MaterialSymbols.push_pin, M3.onPrimary, M3.primary))
    }
}

/** QQ sysface emo-code lead char: `new String(new char[]{20, (char) localId})` (see QQSysFaceUtil.g). */
private const val FACE_LEAD = '\u0014'

/** True for chars that only ever appear as the (failed) anchor of an emoji span — never real text. */
private fun isFaceGarbage(c: Char): Boolean {
    val code = c.code
    return (code in 0x01..0x1F && code != 0x09 && code != 0x0A && code != 0x0D) ||
        code in 0xE000..0xF8FF || // private use area (face anchors)
        code == 0xFFFD            // replacement char
}

/**
 * The conversation-list preview ([RecentContactChatItem.SummaryInfo].msgSummary) renders emoji via
 * `IEmojiSpanService.createEmojiSpanText`, which emits a ``+index emo-code wrapped in an
 * EmoticonSpan. On this older watch build the span's drawable often fails to load, so the raw
 * control-char code leaks and shows as garbage glyphs ("random utf8"). We replace those unrendered
 * face tokens with a readable "[表情]" placeholder while keeping all other text and spans intact.
 */
fun sanitizeRecentSummary(cs: CharSequence): CharSequence {
    if (cs.isEmpty()) return cs

    // Compute replacement ranges in original coordinates.
    val ranges = ArrayList<IntArray>()
    var i = 0
    while (i < cs.length) {
        val c = cs[i]
        when {
            c == FACE_LEAD -> {
                ranges.add(intArrayOf(i, minOf(cs.length, i + 2))) // lead + index char
                i += 2
            }
            isFaceGarbage(c) -> {
                val start = i
                i++
                while (i < cs.length && isFaceGarbage(cs[i])) i++
                ranges.add(intArrayOf(start, i))
            }
            else -> i++
        }
    }
    if (ranges.isEmpty()) return cs

    // Diagnostic: dump raw char codes so token shapes can be verified from the log (log-gated —
    // the per-char hex formatting is pure waste when logging is off).
    if (Utils.loggingEnabled) {
        val codes = StringBuilder()
        for (ch in cs) codes.append(String.format("%04X ", ch.code))
        Utils.log("RecentSummary sanitize: ${ranges.size} face token(s), raw codes=$codes")
    }

    val out = SpannableStringBuilder(cs)
    for (k in ranges.indices.reversed()) {
        val r = ranges[k]
        out.replace(r[0], r[1], "[表情]")
    }
    return out
}

object RecentContacts {
    val map = mutableMapOf<String, Data>()
    fun get(peerUin: String?) = map[peerUin]

    // 免打扰/屏蔽 (muted) state per peerUid, kept independently of [map] so the live unread badges
    // (MainNav / RichTitlebar) can exclude DND chats even before their list row has rendered — the
    // kernel listeners push unread counts and RecentContactInfo before the WatchRecentItemBuilder
    // binds, so [map] may not have the entry yet. Both the list-render path and the kernel-listener
    // paths feed this via [recordMuted].
    // ConcurrentHashMap: written off the UI thread by the kernel UnreadListener path
    // (MainNav/RichTitlebar put() → recordMuted) as well as on the UI thread (list bind), and read on
    // the UI thread via isDisturb. A plain HashMap with concurrent get/put can corrupt during a resize.
    private val mutedMap = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * A chat is muted (DND) when isMsgDisturb is set OR shieldFlag is outside {0,1}: 0 = 好友默认,
     * 1 = 群正常接收并提醒, anything else (2 收进群助手 / 3 接收但不提醒 / 4 屏蔽) is muted. Mirrors
     * WatchRecentItemBuilder.l()'s grey-badge condition.
     */
    fun isMuted(isMsgDisturb: Boolean, shieldFlag: Long): Boolean =
        isMsgDisturb || (shieldFlag != 0L && shieldFlag != 1L)

    fun recordMuted(peerUid: String?, muted: Boolean) {
        if (!peerUid.isNullOrEmpty()) mutedMap[peerUid] = muted
    }

    /** Whether this peer is muted (DND). Prefers the live [mutedMap], falls back to [map]. */
    fun isDisturb(peerUid: String?): Boolean {
        if (peerUid == null) return false // ConcurrentHashMap.get(null) would throw
        return mutedMap[peerUid] ?: map[peerUid]?.disturb ?: false
    }
    class Data(
        val raw: RecentContactInfo,
        val unreadCntCached: Int,
        // 免打扰/屏蔽: true when the chat-list greys the unread badge, i.e. isMsgDisturb (item.p)
        // OR shieldFlag (item.q) not in {0,1} — same condition as WatchRecentItemBuilder.l().
        val disturb: Boolean = false,
    ) {
        val atType get() = raw.atType
    }

    @Mixin
    abstract class Hook : WatchRecentItemBuilder() {
        /** Sanitize the item's stored msgSummary in place so every setText path reads clean text. */
        fun sanitizeItem(item: RecentContactChatItem) {
            item.h?.let { info -> info.a?.let { info.a = sanitizeRecentSummary(it) } }
        }

        override fun t(item: RecentContactChatItem, holder: WatchRecentContactHolder) {
            // Grey badge (muted) = isMsgDisturb (item.p) OR shieldFlag (item.q) not in {0,1}.
            val muted = isMuted(item.p, item.q)
            if (Utils.loggingEnabled) {
                Utils.log("load recent contact: ${item.a.peerName}, unreadCnt: ${item.a.unreadCnt}, peerUid: ${item.a.peerUid}, muted=$muted (disturb=${item.p}, shieldFlag=${item.q})")
            }
            map[item.a.peerUid] = Data(
                item.a,
                item.a.unreadCnt.toInt(),
                muted
            )
            recordMuted(item.a.peerUid, muted)
            // Replace unrendered emoji garbage in the preview with a "[表情]" placeholder before binding.
            sanitizeItem(item)
            super.t(item, holder)
            swapPinIcon(holder)
            materializeChatRow(holder)
            fontWrapChatRow(holder)
            applyRecentOnlineBadge(holder, item)
        }

        /**
         * Summary-only partial update (fires when returning from a chat / on incremental list diff):
         * `m()` → `p()` → `q()` sets `item.summaryInfo.msgSummary` straight onto the TextView,
         * bypassing the full bind in [t]. Sanitize here too or the garbage glyphs come back.
         */
        override fun q(item: RecentContactChatItem, holder: WatchRecentContactHolder) {
            sanitizeItem(item)
            super.q(item, holder)
            fontWrapChatRow(holder)
        }

        /** Partial-bind dispatcher also calls setText directly when a payload list is present. */
        override fun m(
            holder: com.tencent.qqnt.chats.core.adapter.holder.BaseChatViewHolder<com.tencent.qqnt.chats.core.adapter.itemdata.BaseChatItem>,
            item: RecentContactChatItem,
            payload: List<Any?>,
        ) {
            sanitizeItem(item)
            super.m(holder, item, payload)
            if (holder is WatchRecentContactHolder) fontWrapChatRow(holder)
        }
    }
}
