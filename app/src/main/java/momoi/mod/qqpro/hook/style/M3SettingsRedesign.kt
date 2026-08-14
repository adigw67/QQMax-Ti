package momoi.mod.qqpro.hook.style
import momoi.mod.qqpro.lib.setClipToOutlineCompat

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.CompoundButton
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tencent.qqnt.kernel.nativeinterface.MemberRole
import com.tencent.widget.SingleLineTextView
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.forEachAll
import momoi.mod.qqpro.hook.GroupMute
import momoi.mod.qqpro.hook.action.CurrentContact
import momoi.mod.qqpro.hook.action.CurrentGroupMembers
import momoi.mod.qqpro.hook.action.SelfContact
import momoi.mod.qqpro.hook.action.isGroup
import momoi.mod.qqpro.hook.view.ConfirmFragment
import momoi.mod.qqpro.keepEmojiFitToText
import momoi.mod.qqpro.lib.FILL
import momoi.mod.qqpro.lib.WRAP
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3Button
import momoi.mod.qqpro.lib.material.M3Card
import momoi.mod.qqpro.lib.material.M3ListItem
import momoi.mod.qqpro.lib.material.M3Switch
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.lib.SwipeBackLayout
import momoi.mod.qqpro.lib.material.symbolImage
import momoi.mod.qqpro.util.Utils
import java.util.WeakHashMap

/**
 * Material 3 rebuild of the native settings screens (self/me page, friend & group chat-settings
 * pages). Gated behind [momoi.mod.qqpro.Settings.useM3Settings] and invoked from the existing
 * onCreateView (Y) hooks in [CardMarginUnify].
 *
 * Strategy — harvest, don't reimplement: the native fragment still builds its own view tree (with all
 * its click handlers, switches and async observers wired up). We keep that tree alive but hidden, read
 * each "card" out of it (title / subtitle / switch / click target), and present a fresh M3 list whose
 * rows DELEGATE back to the native views (`performClick()` for actions, mirroring the native
 * [CompoundButton] for switches). Nothing about the backend is re-implemented, so a harvest miss at
 * worst renders an inert row — it can never corrupt account state. Any failure falls back to the
 * native screen (the caller keeps the original view).
 *
 * Top-level (non-@Mixin) on purpose: anonymous listener classes here are safe, and the public
 * file-class is reachable from the copied @Mixin bodies (see qqpro-mixin-anon-class / mixin-helper).
 */

private fun View.byIdName(name: String): View? {
    val id = resources.getIdentifier(name, "id", context.packageName)
    return if (id != 0) findViewById(id) else null
}

/** A row distilled from one native settings card. */
private class HarvestedRow(
    var title: String,
    val subtitle: String?,
    val nativeSwitch: CompoundButton?,
    val click: () -> Unit,
    val destructive: Boolean,
    // For action rows that actually toggle a state (置顶/免打扰 in the long-press sheet): render as a
    // switch reflecting the current state inferred from the label (取消X = currently on). Null = not a toggle.
    var virtualChecked: Boolean? = null,
)

/** Walk the direct children of [container] (each a native card) into [HarvestedRow]s. */
private fun harvest(container: ViewGroup): List<HarvestedRow> {
    val rows = ArrayList<HarvestedRow>()
    for (i in 0 until container.childCount) {
        harvestRow(container.getChildAt(i))?.let { rows.add(it) }
    }
    return rows
}

/** Distil one native settings card into a [HarvestedRow], or null if it has no usable label. */
private fun harvestRow(card: View): HarvestedRow? {
    val texts = ArrayList<TextView>()
    var sw: CompoundButton? = null
    // CompoundButton extends TextView, so test it FIRST and exclude it from the label list. Only a
    // VISIBLE switch counts: item_setting_with_switch always contains a Switch but hides it (GONE)
    // for action rows like 清空消息 — those must render as a ">" row, not a toggle.
    if (card is CompoundButton) { if (card.visibility == View.VISIBLE) sw = card }
    else if (card is TextView && !card.text.isNullOrBlank()) texts.add(card)
    (card as? ViewGroup)?.forEachAll { v ->
        when {
            v is CompoundButton -> { if (sw == null && v.visibility == View.VISIBLE) sw = v }
            v is TextView && !v.text.isNullOrBlank() -> texts.add(v)
        }
    }
    val title = brandFix(texts.firstOrNull()?.text?.toString()?.trim().orEmpty())
    if (title.isEmpty()) return null
    val subtitle = texts.getOrNull(1)?.text?.toString()?.trim()?.let(::brandFix)?.takeIf { it != title }
    return HarvestedRow(
        title = title,
        subtitle = subtitle,
        nativeSwitch = sw,
        click = { card.performClick() },
        destructive = isDestructive(title),
    )
}

/**
 * The base app's resources still brand some settings entries as "QQPro"/"NWear-QQ" (baked into
 * resources.arsc, which the mixin build can't recompile). Rewrite to "QQMax-Ti"（或去掉旧品牌字样）
 * as titles are harvested so the M3 list
 * matches the rest of the app's branding. Mirrors [SelfTabClearCredit] for the native self tab.
 */
private fun brandFix(text: String): String =
    text.replace("QQ Pro", "QQMax-Ti")
        .replace("QQPro", "QQMax-Ti")
        .replace("QQ Max", "QQMax-Ti")
        .replace("NWear-QQ", "")
        .replace("NWearQQ", "")

private fun isDestructive(title: String): Boolean =
    listOf("清空", "清除", "删除", "退出", "注销", "登出", "解散").any { title.contains(it) }

/** Map a Chinese row title to an official Material Symbol path; null lets the caller pick a default. */
private fun iconPathForTitle(title: String): String? = with(MaterialSymbols) {
    when {
        title.contains("资料") -> manage_accounts
        title.contains("群名") || title.contains("改名") || title.contains("名称") -> edit
        title.contains("昵称") || title.contains("名片") -> badge
        title.contains("头像") -> account_circle
        title.contains("二维码") && title.contains("扫") -> qr_code_scanner
        title.contains("扫") -> qr_code_scanner
        title.contains("二维码") -> qr_code
        title.contains("翻译") -> translate
        title.contains("公告") -> campaign
        title.contains("搜索") || title.contains("查找") -> search
        title.contains("空间") || title.contains("动态") || title.contains("说说") -> star
        title.contains("免打扰") || title.contains("打扰") -> notifications_off
        title.contains("通知") || title.contains("提醒") -> notifications
        title.contains("置顶") -> push_pin
        title.contains("缓存") -> cleaning_services
        title.contains("清空") || title.contains("清除") || title.contains("聊天记录") -> delete_sweep
        title.contains("删除") -> delete
        title.contains("移除") || title.contains("移出") -> person_remove
        title.contains("退出登录") || title.contains("注销") || title.contains("登出") -> logout
        title.contains("退出") || title.contains("解散") -> logout
        title.contains("切换") && title.contains("号") -> switch_account
        title.contains("设置") -> settings
        title.contains("关于") -> info
        title.contains("帮助") || title.contains("反馈") || title.contains("问题") -> help
        title.contains("隐私") -> lock
        title.contains("安全") -> verified_user
        title.contains("装扮") || title.contains("个性") || title.contains("主题") || title.contains("皮肤") -> palette
        title.contains("夜间") || title.contains("深色") -> dark_mode
        title.contains("存储") || title.contains("空间") -> storage
        title.contains("收藏") -> star
        title.contains("文件") -> folder
        title.contains("成员") -> group
        title.contains("群") -> group
        title.contains("好友") || title.contains("添加") -> person_add
        title.contains("同步") -> sync
        else -> null
    }
}

private fun withAlpha(color: Int, alpha: Int): Int = (alpha shl 24) or (color and 0xFFFFFF)

/** A bold section label above an M3 list. */
private fun sectionLabel(ctx: Context, text: String): TextView = TextView(ctx).apply {
    this.text = text
    textSize = 11f
    setTextColor(M3.onSurfaceVariant)
    typeface = Typeface.DEFAULT_BOLD
    setPadding(14.dp, 12.dp, 14.dp, 6.dp)
    layoutParams = LinearLayout.LayoutParams(FILL, WRAP)
}

private fun divider(ctx: Context): View = View(ctx).apply {
    setBackgroundColor(M3.outlineVariant)
    layoutParams = LinearLayout.LayoutParams(FILL, 1.dp).apply {
        marginStart = 54.dp; marginEnd = 8.dp
    }
}

/** Mirror a native [CompoundButton]'s checked state onto the [M3Switch] while it's on screen. */
private fun mirrorSwitch(m3: M3Switch, native: CompoundButton) {
    m3.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        private val poll = object : Runnable {
            override fun run() {
                if (!m3.isAttachedToWindow) return
                m3.setChecked(native.isChecked, notify = false)
                m3.postDelayed(this, 350)
            }
        }
        override fun onViewAttachedToWindow(v: View) { m3.removeCallbacks(poll); m3.post(poll) }
        override fun onViewDetachedFromWindow(v: View) { m3.removeCallbacks(poll) }
    })
}

/** An empty M3 surface card to hold list rows. */
private fun newListCard(ctx: Context): M3Card = M3Card(ctx).contentPadding(4.dp).apply {
    setClipToOutlineCompat(true)  // clip row ripples/dividers to the card's rounded corners
    layoutParams = LinearLayout.LayoutParams(FILL, WRAP).apply {
        marginStart = 4.dp; marginEnd = 4.dp; topMargin = 4.dp; bottomMargin = 4.dp
    }
}

/** Build one M3 list row (leading icon disc + title/subtitle + trailing switch or chevron). */
private fun buildItem(ctx: Context, row: HarvestedRow, afterClick: (() -> Unit)? = null): View {
    val item = M3ListItem(ctx).dense().title(row.title)
    row.subtitle?.let { item.subtitle(it) }

    val fg = if (row.destructive) M3.error else M3.primary
    val bg = withAlpha(if (row.destructive) M3.error else M3.primary, 0x33)
    val path = iconPathForTitle(row.title) ?: MaterialSymbols.tune
    val disc = ImageView(ctx).apply { setImageDrawable(MaterialSymbol.circled(path, fg, bg)) }
    item.leading(disc, 30)

    val click = { row.click(); afterClick?.invoke(); Unit }
    val native = row.nativeSwitch
    when {
        native != null -> {
            val sw = M3Switch(ctx)
            sw.setChecked(native.isChecked, notify = false)
            sw.onChange = { row.click() } // a toggle keeps the sheet open (no afterClick)
            item.trailing(sw)
            mirrorSwitch(sw, native)
            item.setOnClickListener { sw.toggle() }
        }
        row.virtualChecked != null -> {
            // 置顶/免打扰: the native menu exposes only the ONE action matching the CURRENT state
            // ("免打扰" to enable, or "取消免打扰" to disable) — it's a one-shot performClick, NOT a
            // reusable two-way toggle. So a flip runs that single action and then closes the sheet;
            // re-opening re-harvests the now-flipped state deterministically (from which action the
            // native menu shows). Without the dismiss, flipping back re-runs the SAME one-way action
            // and desyncs ("turn on then off → still on"), and the captured label never refreshes.
            val sw = M3Switch(ctx)
            sw.setChecked(row.virtualChecked == true, notify = false)
            sw.onChange = { row.click(); afterClick?.invoke() }
            item.trailing(sw)
            item.setOnClickListener { sw.toggle() }
        }
        else -> {
            item.trailing(symbolImage(ctx, MaterialSymbols.chevron_right, M3.onSurfaceVariant, 18))
            item.setOnClickListener { click() }
        }
    }
    return item
}

/** Append one row to [card], inserting an inset divider before it when the card already has rows. */
private fun appendRow(ctx: Context, card: M3Card, row: HarvestedRow, afterClick: (() -> Unit)? = null) {
    if (card.childCount > 0) card.addView(divider(ctx))
    card.addView(buildItem(ctx, row, afterClick), LinearLayout.LayoutParams(FILL, WRAP))
}

/** Insert one row at the TOP of [card] (used for late-injected custom options like 群公告/搜索). */
private fun prependRow(ctx: Context, card: M3Card, row: HarvestedRow, afterClick: (() -> Unit)? = null) {
    if (card.childCount > 0) card.addView(divider(ctx), 0)
    card.addView(buildItem(ctx, row, afterClick), 0, LinearLayout.LayoutParams(FILL, WRAP))
}

/** Build one M3 surface card holding all [rows] as M3 list items, with inset dividers between them. */
private fun buildList(ctx: Context, rows: List<HarvestedRow>, afterClick: (() -> Unit)? = null): M3Card {
    val card = newListCard(ctx)
    rows.forEach { appendRow(ctx, card, it, afterClick) }
    return card
}

private const val MUTE_ROW_TAG = "qqpro_group_mute_row"

/**
 * Append a 全员禁言 (whole-group mute) switch to the group-settings [card] — visible ONLY to the group
 * owner/admin (role resolved async, so the row appears a beat after the list). The switch syncs to the
 * current mute state ([GroupMute.isMuted]); enabling asks for confirmation first (it silences the whole
 * group), disabling is immediate; on server reject the switch reverts. See [GroupMute].
 */
private fun appendGroupMuteToggle(ctx: Context, card: M3Card) {
    if (!Settings.groupWholeMute.value || !CurrentContact.isGroup) return
    val peerUid = CurrentContact.peerUid
    if (peerUid.isEmpty()) return
    CurrentGroupMembers.get(SelfContact.peerUid) { self ->
        val privileged = self.role == MemberRole.OWNER || self.role == MemberRole.ADMIN
        if (!privileged) { Utils.log("GroupMute: self not owner/admin (role=${self.role}), no toggle"); return@get }
        card.post {
            runCatching { buildAndAppendMuteRow(ctx, card, peerUid) }
                .onFailure { Utils.log("GroupMute: append row failed: $it") }
        }
    }
}

private fun buildAndAppendMuteRow(ctx: Context, card: M3Card, peerUid: String) {
    // The async role callback can fire more than once (member list re-delivery) — never double-append.
    if (card.findViewWithTag<View>(MUTE_ROW_TAG) != null) return

    val item = M3ListItem(ctx).dense().title("全员禁言").subtitle("开启后仅群主和管理员可发言")
    item.tag = MUTE_ROW_TAG
    val fg = M3.error
    val disc = ImageView(ctx).apply {
        setImageDrawable(MaterialSymbol.circled(MaterialSymbols.mic_off, fg, withAlpha(M3.error, 0x33)))
    }
    item.leading(disc, 30)

    val sw = M3Switch(ctx)
    sw.setChecked(GroupMute.isMuted(peerUid), notify = false)
    item.trailing(sw)

    fun fire(target: Boolean) {
        GroupMute.setMuted(peerUid, target) { ok ->
            if (ok) {
                Utils.toast(Utils.application, if (target) "已开启全员禁言" else "已关闭全员禁言")
            } else {
                sw.setChecked(!target, notify = false) // revert to the real state on server reject
                Utils.toast(Utils.application, "操作失败")
            }
        }
    }

    sw.onChange = { want ->
        if (want) {
            // Enabling silences everyone — undo the visual flip, confirm first, then commit + fire.
            // (The brief revert is hidden behind the confirm dialog; a cancel/swipe leaves it off.)
            sw.setChecked(false, notify = false)
            val fm = (ctx.findActivity() as? androidx.fragment.app.FragmentActivity)?.supportFragmentManager
            if (fm != null) {
                ConfirmFragment(title = "确定开启全员禁言？", confirmLabel = "开启") {
                    sw.setChecked(true, notify = false); fire(true)
                }.show(fm, "qqpro_group_mute_confirm")
            } else {
                sw.setChecked(true, notify = false); fire(true)
            }
        } else {
            fire(false) // un-mute is harmless — no confirm
        }
    }
    item.setOnClickListener { sw.toggle() }

    if (card.childCount > 0) card.addView(divider(ctx))
    card.addView(item, LinearLayout.LayoutParams(FILL, WRAP))
    Utils.log("GroupMute: 全员禁言 toggle appended (muted=${GroupMute.isMuted(peerUid)})")
}

/**
 * Mirror late additions to the native [container] into [card]. QQPro feature hooks (群公告 /
 * 搜索聊天记录 …) inject their cards into the native settings container in onViewCreated — AFTER our Y
 * rebuild — so they'd otherwise land in the hidden native tree. Watch for added children and harvest
 * them into the M3 list too.
 */
private fun attachLateInjections(container: ViewGroup, ctx: Context, card: M3Card) {
    container.setOnHierarchyChangeListener(object : ViewGroup.OnHierarchyChangeListener {
        override fun onChildViewAdded(parent: View?, child: View?) {
            child ?: return
            // Injected custom options (群公告/搜索聊天记录…) go to the TOP of the list for quick access.
            child.post { harvestRow(child)?.let { prependRow(ctx, card, it) } }
        }
        override fun onChildViewRemoved(parent: View?, child: View?) {}
    })
}

/** Move [v] out of its current parent so it can be re-added elsewhere. */
private fun detach(v: View?) { (v?.parent as? ViewGroup)?.removeView(v) }

/** The nearest [Activity] up the context wrapper chain, or null. */
private fun Context.findActivity(): Activity? {
    var c: Context? = this
    while (c is ContextWrapper) { if (c is Activity) return c; c = c.baseContext }
    return null
}

/**
 * Wrap [content] in a right-swipe-back gesture that pops via the activity's back dispatcher (routing-
 * safe — identical to pressing the hardware/system back). Honors the global 屏蔽应用内右滑返回 setting.
 */
private fun swipeBackWrap(ctx: Context, content: View): View = SwipeBackLayout(ctx).apply {
    onSwipeBack = {
        // Same as pressing system back: pops the nav stack for these navigated fragments (routing-safe).
        @Suppress("DEPRECATION") ctx.findActivity()?.onBackPressed()
    }
    addView(content, FILL, FILL)
}

/** Wrap [scroll] over the still-live (but hidden) [nativeRoot] so its handlers/observers survive. */
private fun frameOver(ctx: Context, nativeRoot: View, scroll: View): View = FrameLayout(ctx).apply {
    addView(nativeRoot, FrameLayout.LayoutParams(FILL, FILL))
    nativeRoot.visibility = View.GONE
    addView(scroll, FrameLayout.LayoutParams(FILL, FILL))
}

/** A centered bold page-title header. */
private fun titleHeader(ctx: Context, title: String): TextView = TextView(ctx).apply {
    text = title
    textSize = 16f
    setTextColor(M3.onSurface)
    typeface = Typeface.DEFAULT_BOLD
    gravity = Gravity.CENTER
    setPadding(16.dp, 6.dp, 16.dp, 12.dp)
    layoutParams = LinearLayout.LayoutParams(FILL, WRAP)
}

private fun newScroll(ctx: Context): Pair<ScrollView, LinearLayout> {
    val scroll = ScrollView(ctx).apply {
        isFillViewport = true
        isVerticalScrollBarEnabled = false
        setBackgroundColor(M3.surface)
    }
    // 圆屏模式：列表列左右 + 顶/底套圆屏安全区，卡片行与首尾行不被圆边裁切（长按列表项弹出的
    // 设置/操作弹窗也复用此滚动容器）。
    val round = momoi.mod.qqpro.lib.RoundWatch.enabled
    val col = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        if (round) {
            val h = maxOf(4.dp, momoi.mod.qqpro.lib.RoundWatch.horizontalInsetPx(ctx))
            val top = maxOf(12.dp, momoi.mod.qqpro.lib.RoundWatch.safeTopBottomPx(ctx))
            setPadding(h, top, h, 28.dp + momoi.mod.qqpro.lib.RoundWatch.safeTopBottomPx(ctx))
        } else {
            setPadding(4.dp, 12.dp, 4.dp, 28.dp)
        }
    }
    scroll.addView(col, FILL, WRAP)
    return scroll to col
}

/**
 * Rebuild the self/me page (主页第4页) as an M3 header card (avatar + name + QQ + 性别/生日) followed
 * by an M3 list of the operation entries. Returns null on any failure (caller keeps native view).
 */
fun rebuildSelfPage(nativeRoot: View): View? = runCatching {
    val ctx = nativeRoot.context
    val container = nativeRoot.byIdName("operation_container") as? ViewGroup ?: return null
    val rows = harvest(container)
    if (rows.isEmpty()) return null

    val (scroll, col) = newScroll(ctx)
    col.addView(buildSelfHeader(ctx, nativeRoot))
    col.addView(sectionLabel(ctx, "操作"))
    val list = buildList(ctx, rows)
    col.addView(list)
    attachLateInjections(container, ctx, list)

    Utils.log("M3SettingsRedesign: self page rebuilt (${rows.size} rows)")
    frameOver(ctx, nativeRoot, scroll)
}.getOrElse { Utils.log("M3SettingsRedesign: self rebuild failed: $it"); null }

/**
 * Self header: avatar on the LEFT with the name/QQ stacked beside it (left-aligned, a standard M3
 * profile header), then the 性别/生日 block full width below. Reparents the live native views.
 */
private fun buildSelfHeader(ctx: Context, nativeRoot: View): View {
    val card = M3Card(ctx).contentPadding(12.dp).apply {
        layoutParams = LinearLayout.LayoutParams(FILL, WRAP).apply {
            marginStart = 4.dp; marginEnd = 4.dp; topMargin = 4.dp; bottomMargin = 4.dp
        }
    }

    // Avatar + (name over QQ) row, left-aligned.
    val row = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        layoutParams = LinearLayout.LayoutParams(FILL, WRAP)
    }
    nativeRoot.byIdName("self_avatar")?.let { avatar ->
        detach(avatar)
        row.addView(avatar, LinearLayout.LayoutParams(48.dp, 48.dp).apply { marginEnd = 12.dp })
    }
    val textCol = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
    }
    nativeRoot.byIdName("self_name")?.let { name ->
        detach(name)
        // self_name is usually QQ's SingleLineTextView (a plain View, NOT a TextView), so an
        // `as? TextView` recolor silently no-ops and the name keeps its native color (invisible in
        // some themes). Handle both, mirroring buildSettingFrameHeader.
        when (name) {
            is SingleLineTextView -> { name.setTextSize(16f); name.setTextColor(M3.onSurface); name.keepEmojiFitToText() }
            is TextView -> name.apply { textSize = 16f; setTextColor(M3.onSurface); gravity = Gravity.START }
        }
        textCol.addView(name, LinearLayout.LayoutParams(FILL, WRAP))
    }
    nativeRoot.byIdName("self_qq")?.let { qq ->
        detach(qq)
        (qq as? TextView)?.apply { textSize = 12f; setTextColor(M3.onSurfaceVariant); gravity = Gravity.START }
        textCol.addView(qq, LinearLayout.LayoutParams(FILL, WRAP).apply { topMargin = 3.dp })
    }
    row.addView(textCol)
    card.addView(row)

    nativeRoot.byIdName("self_info")?.let { info ->
        detach(info)
        card.addView(info, LinearLayout.LayoutParams(FILL, WRAP).apply { topMargin = 12.dp })
        (info as? ViewGroup)?.styleGenderBirthdayChipsWithRetry()
    }
    return card
}

/**
 * Rebuild a friend/group chat-settings list (FriendSettingFragment / TroopSettingFragment): a title
 * header followed by the M3 list of switch/action rows. Returns null on any failure.
 */
fun rebuildSettingList(
    nativeRoot: View,
    title: String,
    containerId: String = "setting_container",
    swipeBack: Boolean = false,
    dismissTarget: androidx.fragment.app.DialogFragment? = null,
    syntheticToggles: Boolean = false,
    groupMute: Boolean = false,
): View? = runCatching {
    val ctx = nativeRoot.context
    val container = nativeRoot.byIdName(containerId) as? ViewGroup ?: return null
    val rows = harvest(container)
    if (rows.isEmpty()) return null

    // Render 置顶/免打扰 action rows as state switches (state inferred from the 取消X label).
    if (syntheticToggles) rows.forEach { r ->
        if (r.nativeSwitch == null && (r.title.contains("置顶") || r.title.contains("免打扰"))) {
            // Infer state from the native action label FIRST ("取消X" = currently on), ...
            r.virtualChecked = r.title.contains("取消")
            // ...then drop the action verb: as a switch the row should read the plain state noun
            // ("免打扰" / "置顶会话"), not "取消免打扰" / "开启免打扰" — the switch already shows on/off.
            r.title = if (r.title.contains("置顶")) "置顶会话" else "免打扰"
        }
    }

    // When hosted in a dialog (long-press action sheet), close it after a non-toggle action runs.
    val afterClick: (() -> Unit)? = dismissTarget?.let { d -> { d.dismissAllowingStateLoss() } }

    val (scroll, col) = newScroll(ctx)
    if (title.isNotBlank()) col.addView(titleHeader(ctx, title))
    val list = buildList(ctx, rows, afterClick)
    col.addView(list)
    attachLateInjections(container, ctx, list)
    if (groupMute) appendGroupMuteToggle(ctx, list)

    Utils.log("M3SettingsRedesign: '$title' rebuilt (${rows.size} rows)")
    val content = frameOver(ctx, nativeRoot, scroll)
    when {
        // A dialog swipes to dismiss itself (not the activity back stack).
        dismissTarget != null -> SwipeBackLayout(ctx).apply {
            onSwipeBack = { dismissTarget.dismissAllowingStateLoss() }
            addView(content, FILL, FILL)
        }
        swipeBack -> swipeBackWrap(ctx, content)
        else -> content
    }
}.getOrElse { Utils.log("M3SettingsRedesign: '$title' rebuild failed: $it"); null }

/**
 * Rebuild the change-avatar page ([EditAvatarFragment]): a large avatar preview card plus 相册 / 拍照
 * Material buttons that delegate to the native WatchButtons (which carry the real pick/upload logic).
 */
fun rebuildAvatarPage(nativeRoot: View): View? = runCatching {
    val ctx = nativeRoot.context
    val avatar = nativeRoot.byIdName("avatar")
    val selectPic = nativeRoot.byIdName("select_pic")
    val camera = nativeRoot.byIdName("camera")
    if (selectPic == null && camera == null) return null

    val (scroll, col) = newScroll(ctx)
    col.addView(titleHeader(ctx, "修改头像"))

    val previewCard = M3Card(ctx).contentPadding(20.dp).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(FILL, WRAP).apply {
            marginStart = 4.dp; marginEnd = 4.dp; topMargin = 4.dp; bottomMargin = 8.dp
        }
    }
    avatar?.let {
        detach(it)
        previewCard.addView(it, LinearLayout.LayoutParams(96.dp, 96.dp).apply { gravity = Gravity.CENTER_HORIZONTAL })
    }
    col.addView(previewCard)

    selectPic?.let { col.addView(m3ActionButton(ctx, "相册", M3Button.Variant.FILLED) { it.performClick() }) }
    camera?.let { col.addView(m3ActionButton(ctx, "拍照", M3Button.Variant.TONAL) { it.performClick() }) }

    Utils.log("M3SettingsRedesign: avatar page rebuilt")
    swipeBackWrap(ctx, frameOver(ctx, nativeRoot, scroll))
}.getOrElse { Utils.log("M3SettingsRedesign: avatar page rebuild failed: $it"); null }

/** A full-width Material button row that runs [onClick]. */
private fun m3ActionButton(ctx: Context, label: String, variant: M3Button.Variant, onClick: () -> Unit): M3Button =
    M3Button(ctx).variant(variant).apply {
        text = label
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(FILL, 44.dp).apply {
            marginStart = 8.dp; marginEnd = 8.dp; topMargin = 8.dp
        }
    }

// SettingFrame (the swipe-in chat about/settings panel) is built programmatically with no container
// id and may be returned again from a cached contentView, so cache the rebuilt frame per native view.
private val rebuiltFrames = WeakHashMap<View, View>()

/**
 * Rebuild the chat settings panel ([com.tencent.watch.aio_impl.ui.frames.SettingFrame]) — the
 * avatar/name/QQ-number header plus the 群成员/群公告/群聊设置/退出群 (or DM) entry cards — into the
 * same M3 header card + list. The native view is a ScrollView → vertical LinearLayout of
 * [avatar, nick, peerId, CustomInfoView, items...]; classify by type, reparent the header views (so
 * the fragment's async avatar/name/QQ updates and the DM extra-info chips still land on them) and
 * harvest the rest. Returns null on any failure (caller keeps the native panel).
 */
fun rebuildSettingFrame(nativeScroll: View): View? = runCatching {
    rebuiltFrames[nativeScroll]?.let { cached ->
        (cached.parent as? ViewGroup)?.removeView(cached)
        return cached
    }
    val ctx = nativeScroll.context
    val column = (nativeScroll as? ViewGroup)?.getChildAt(0) as? ViewGroup ?: return null

    var avatar: View? = null
    var nick: View? = null
    var peerId: View? = null
    var info: View? = null
    val itemViews = ArrayList<View>()
    for (i in 0 until column.childCount) {
        val c = column.getChildAt(i)
        when {
            c.javaClass.simpleName == "WatchAvatarView" -> avatar = c
            c.javaClass.simpleName == "SingleLineTextView" -> nick = c
            c.javaClass.simpleName == "CustomInfoView" -> info = c
            peerId == null && c is TextView -> peerId = c
            else -> itemViews.add(c)
        }
    }
    val rows = itemViews.mapNotNull { harvestRow(it) }
    if (rows.isEmpty()) return null

    val (scroll, col) = newScroll(ctx)
    col.addView(buildSettingFrameHeader(ctx, avatar, nick, peerId, info))
    val list = buildList(ctx, rows)
    col.addView(list)
    attachLateInjections(column, ctx, list)

    Utils.log("M3SettingsRedesign: SettingFrame rebuilt (${rows.size} rows)")
    val frame = frameOver(ctx, nativeScroll, scroll)
    rebuiltFrames[nativeScroll] = frame
    frame
}.getOrElse { Utils.log("M3SettingsRedesign: SettingFrame rebuild failed: $it"); null }

/** Header card for the chat settings panel: reparents the live avatar / nick / QQ-number / info views. */
private fun buildSettingFrameHeader(ctx: Context, avatar: View?, nick: View?, peerId: View?, info: View?): View {
    val card = M3Card(ctx).contentPadding(12.dp).apply {
        gravity = Gravity.CENTER_HORIZONTAL
        layoutParams = LinearLayout.LayoutParams(FILL, WRAP).apply {
            marginStart = 4.dp; marginEnd = 4.dp; topMargin = 4.dp; bottomMargin = 4.dp
        }
    }
    avatar?.let {
        detach(it)
        card.addView(it, LinearLayout.LayoutParams(56.dp, 56.dp).apply { gravity = Gravity.CENTER_HORIZONTAL })
    }
    nick?.let {
        detach(it)
        // The name's face emoji are baked at QQ's default size; shrink them to match the 16sp title
        // (and re-fit when the async name update lands) so they don't dwarf the text. The name view
        // is usually QQ's SingleLineTextView (a plain View, NOT a TextView), so handle both.
        when (it) {
            is SingleLineTextView -> {
                it.setTextSize(15f); it.setTextColor(M3.onSurface)
                it.keepEmojiFitToText()
            }
            is TextView -> it.apply {
                textSize = 15f; setTextColor(M3.onSurface); gravity = Gravity.CENTER
                keepEmojiFitToText()
            }
        }
        card.addView(it, LinearLayout.LayoutParams(FILL, WRAP).apply {
            topMargin = 8.dp; gravity = Gravity.CENTER_HORIZONTAL
        })
    }
    peerId?.let {
        detach(it)
        (it as? TextView)?.apply { textSize = 12f; setTextColor(M3.onSurfaceVariant); gravity = Gravity.CENTER }
        card.addView(it, LinearLayout.LayoutParams(FILL, WRAP).apply {
            topMargin = 2.dp; gravity = Gravity.CENTER_HORIZONTAL
        })
    }
    info?.let {
        detach(it)
        card.addView(it, LinearLayout.LayoutParams(FILL, WRAP).apply { topMargin = 12.dp })
        (it as? ViewGroup)?.styleGenderBirthdayChipsWithRetry()
    }
    return card
}
