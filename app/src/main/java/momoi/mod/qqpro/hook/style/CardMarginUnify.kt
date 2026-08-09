package momoi.mod.qqpro.hook.style

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import momoi.mod.qqpro.lib.material.M3
import com.tencent.qqnt.watch.selftab.ui.SelfFragment
import com.tencent.qqnt.watch.setting.FriendSettingFragment
import com.tencent.qqnt.watch.troop.ui.member.ui.GroupMemberFragment
import com.tencent.qqnt.watch.troop.ui.setting.TroopSettingFragment
import com.tencent.qqnt.watch.selftab.ui.edit.EditAvatarFragment
import com.tencent.qqnt.watch.selftab.ui.edit.SelfEditProfileFragment
import com.tencent.qqnt.watch.gallery.preview.MediaBrowserLongClickMenuFragment
import com.tencent.qqnt.watch.ui.componet.select.SelectDialogFragment
import com.tencent.watch.aio_impl.ui.frames.SettingFrame
import com.tencent.watch.ime.input.ChooseInputFragment
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.asGroupOrNull
import momoi.mod.qqpro.hook.contact.ProfileNameView
import momoi.mod.qqpro.hook.translate.TranslateAll
import momoi.mod.qqpro.findAll
import momoi.mod.qqpro.forEachAll
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.onChildAttached
import momoi.mod.qqpro.lib.rippleTouch
import momoi.mod.qqpro.util.Utils

/** The single, unified gap used between every card across the app. */
const val CARD_MARGIN_DP = 2

/** Set an even [dp] margin on all four sides, if this view supports margins. */
fun View.uniformMargin(dp: Int) {
    (layoutParams as? ViewGroup.MarginLayoutParams)?.let {
        it.setMargins(dp.dp, dp.dp, dp.dp, dp.dp)
        requestLayout()
    }
}

/**
 * Card spacing that looks visually even everywhere. Adjacent cards' margins add up between
 * stacked cards (bottom + top = 2 margins) while a card's edge gap is only 1 margin, so the
 * horizontal margin is set to 2x the vertical one — making every visible gap == 2*CARD_MARGIN_DP.
 */
fun View.cardMargin() {
    val v = CARD_MARGIN_DP.dp
    val h = (2 * CARD_MARGIN_DP).dp
    (layoutParams as? ViewGroup.MarginLayoutParams)?.let {
        it.setMargins(h, v, h, v)
        it.marginStart = h
        it.marginEnd = h
        requestLayout()
    }
}

/**
 * Normalize every "card" in this view tree to CARD_MARGIN_DP. A card is any view that has a
 * background and sits as a direct child of a LinearLayout list (the shape shared by
 * setting_item / item_self_operation / item_setting_with_switch across the app). Chat message
 * lists are RecyclerView-based and are intentionally not touched here.
 */
fun ViewGroup.normalizeListCards() {
    forEachAll { view ->
        if (view.background != null &&
            view.parent is LinearLayout &&
            view.layoutParams is ViewGroup.MarginLayoutParams
        ) {
            view.cardMargin()
        }
    }
}

/**
 * The gender card and birthday card (inside a CustomInfoView) sit side-by-side with a
 * 4dp+4dp = 8dp horizontal gap, far larger than the vertical gap down to the next card.
 * Shrink each side to half of CARD_MARGIN_DP so the horizontal gap equals the vertical one.
 */
fun ViewGroup.fixGenderBirthdayGap() {
    val customInfo = findAll { it.javaClass.simpleName == "CustomInfoView" }?.asGroupOrNull() ?: return
    // The block has no background, so normalizeListCards skips it — give it the same card spacing
    // so its side inset (and the gap below it) match the cards.
    customInfo.cardMargin()
    val inner = customInfo.getChildAt(0)?.asGroupOrNull() ?: return
    if (inner.childCount < 2) return
    // Gap between the two side-by-side cards = marginEnd + marginStart = 2*CARD_MARGIN_DP,
    // equal to every other visible gap.
    (inner.getChildAt(0).layoutParams as? ViewGroup.MarginLayoutParams)?.marginEnd = CARD_MARGIN_DP.dp
    (inner.getChildAt(1).layoutParams as? ViewGroup.MarginLayoutParams)?.marginStart = CARD_MARGIN_DP.dp
}

/** Walk up to the nearest clickable ancestor (the chip "card" wrapping an inner view). */
fun View.nearestClickableAncestor(): View? {
    var v: View? = this
    while (v != null) { if (v.isClickable) return v; v = v.parent as? View }
    return null
}

/**
 * Theme + tighten the native 性别/生日 (gender/birthday) chips, found by their stable resource ids
 * (`gender_icon`, `birthday_date`) so it works regardless of the page's wrapper structure (the older
 * [fixGenderBirthdayGap] assumed a CustomInfoView layout that the self page doesn't share). Recolors
 * the native-blue gender glyph + birthday number to the M3 accent, and shrinks the over-wide gap
 * between the two side-by-side cards to the unified card margin. Safe to call repeatedly. Returns
 * true once it found and styled the chips (so callers can stop retrying).
 */
fun ViewGroup.styleGenderBirthdayChips(): Boolean {
    val pkg = context.packageName
    fun byName(name: String): View? {
        val id = resources.getIdentifier(name, "id", pkg)
        return if (id != 0) findViewById(id) else null
    }
    val gIcon = byName("gender_icon")
    val bDate = byName("birthday_date")
    if (gIcon == null && bDate == null) return false
    (gIcon as? ImageView)?.setColorFilter(M3.primary)
    (bDate as? TextView)?.setTextColor(M3.primary)
    // The gender value text ("男"/"女"/"未知") is native-blue too — theme it to match the birthday.
    (byName("gender_text") as? TextView)?.setTextColor(M3.primary)
    (byName("gender_tv") as? TextView)?.setTextColor(M3.primary)

    val gCard = gIcon?.nearestClickableAncestor()
    val bCard = bDate?.nearestClickableAncestor()
    // Touch response: ripple the tappable 性别/生日 cards (they open the gender/birthday selectors), and
    // give them a themed surface so the native (dark-only) chip background doesn't clash with the M3
    // header card — especially in light mode where the native dark chip would look wrong.
    if (Settings.useM3Settings.value) {
        // Theme the actual card containers (gender_layout/birthday_layout carry the native dark chip
        // background) so both the themed surface AND the ripple land on the visible card — the
        // clickable ancestor isn't that view, so styling it had no effect. Fall back to the ancestor
        // only if the layout ids aren't present.
        val gLayout = byName("gender_layout") ?: gCard
        val bLayout = byName("birthday_layout") ?: bCard
        gLayout?.background = M3.rounded(M3.surfaceContainerHigh, M3.radiusMd)
        bLayout?.background = M3.rounded(M3.surfaceContainerHigh, M3.radiusMd)
        // The native "性别"/"生日" labels are white (dark theme) → invisible on the now-light card.
        // Recolor every label to onSurface, then restore the accent on the value/date views.
        (gLayout as? ViewGroup)?.forEachAll { (it as? TextView)?.setTextColor(M3.onSurface) }
        (bLayout as? ViewGroup)?.forEachAll { (it as? TextView)?.setTextColor(M3.onSurface) }
        (byName("gender_text") as? TextView)?.setTextColor(M3.primary)
        (byName("gender_tv") as? TextView)?.setTextColor(M3.primary)
        (bDate as? TextView)?.setTextColor(M3.primary)
        gLayout?.rippleTouch(clip = false)
        bLayout?.rippleTouch(clip = false)
    }
    if (gCard != null && bCard != null && gCard !== bCard && gCard.parent === bCard.parent) {
        (gCard.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
            rightMargin = CARD_MARGIN_DP.dp; marginEnd = CARD_MARGIN_DP.dp
        }
        (bCard.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
            leftMargin = CARD_MARGIN_DP.dp; marginStart = CARD_MARGIN_DP.dp
        }
        gCard.requestLayout(); bCard.requestLayout()
    }
    return true
}

/** Run [styleGenderBirthdayChips] now and, since some pages fill the chips asynchronously, on a few retries. */
fun ViewGroup.styleGenderBirthdayChipsWithRetry(tries: Int = 0) {
    if (styleGenderBirthdayChips() || tries >= 8) return
    postDelayed({ styleGenderBirthdayChipsWithRetry(tries + 1) }, 150)
}

/**
 * Chat settings panel (friend/group info: avatar + gender/birthday + 群成员/群聊设置/退出群 cards).
 * Unify the entry-card margins and the gender/birthday gap.
 */
@Mixin
class SettingFrameMargins : SettingFrame() {
    override fun Y(p0: LayoutInflater, p1: ViewGroup?, p2: Bundle?): View {
        val root = super.Y(p0, p1, p2)!!
        if (Settings.useM3Settings.value) {
            rebuildSettingFrame(root)?.let { return it }
        }
        (root as? ViewGroup)?.let {
            it.normalizeListCards()
            it.fixGenderBirthdayGap()
            it.styleGenderBirthdayChipsWithRetry()
        }
        Utils.log("CardMarginUnify: chat settings panel normalized")
        return root
    }

    // Opt-in: make the header name (friend or group) wrap to multiple lines and long-press-copyable.
    // Done in onViewCreated (not Y) since the nick text is set asynchronously after the view exists.
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (Settings.profileNameMultiline.value) {
            (view as? ViewGroup)?.let { ProfileNameView.enhance(it) }
        }
    }
}

/** Self / profile page (自我页): same gender/birthday + operation-card unification. */
@Mixin
class SelfInfoMargins : SelfFragment() {
    override fun Y(p0: LayoutInflater, p1: ViewGroup?, p2: Bundle?): View {
        val root = super.Y(p0, p1, p2)!!
        if (Settings.useM3Settings.value) {
            rebuildSelfPage(root)?.let { return it }
        }
        (root as? ViewGroup)?.let {
            it.normalizeListCards()
            it.fixGenderBirthdayGap()
            it.styleGenderBirthdayChipsWithRetry()
        }
        // 简单处理“我的”页底部推广横幅：深色模式下它自带白色背景会刺眼，直接给它换成深色卡片色。
        // 不改结构、不隐藏，只改一个背景色（post 等它晚些被加进来后再找）。
        if (!momoi.mod.qqpro.Settings.lightMode.value) {
            root.postDelayed({ (root as? ViewGroup)?.darkenSelfPromoBanner() }, 1200)
        }
        Utils.log("CardMarginUnify: self page normalized")
        return root
    }
}

/** 把“我的”页底部那条白色推广横幅背景涂成深色（仅深色模式，找不到就算了）。 */
fun ViewGroup.darkenSelfPromoBanner() {
    forEachAll { v ->
        if (v is ViewGroup && v.isClickable && v.childCount >= 3 && v.height > 40) {
            val rect = android.graphics.Rect()
            v.getGlobalVisibleRect(rect)
            if (rect.top < 280 || rect.bottom > 430) return@forEachAll
            var imgCount = 0
            var textCount = 0
            fun count(view: View) {
                if (view is ImageView) imgCount++
                if (view is TextView) textCount++
                if (view is ViewGroup) for (i in 0 until view.childCount) count(view.getChildAt(i))
            }
            count(v)
            if (imgCount >= 2 && textCount >= 1) {
                v.setBackgroundColor(momoi.mod.qqpro.lib.material.M3.surfaceContainer)
            }
        }
    }
}

/**
 * Two side-by-side cards/buttons get a doubled gap between them (each contributes a margin) vs a
 * single edge margin. Give the facing edges half the gap so the gap between them and the gap to
 * the outer edges both come out to 2*CARD_MARGIN_DP. [left] is the start view, [right] the end.
 */
fun fixSideBySide(left: View, right: View) {
    val edge = (2 * CARD_MARGIN_DP).dp
    val face = CARD_MARGIN_DP.dp
    (left.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
        leftMargin = edge; marginStart = edge; rightMargin = face; marginEnd = face
        topMargin = edge; bottomMargin = edge
    }
    (right.layoutParams as? ViewGroup.MarginLayoutParams)?.apply {
        leftMargin = face; marginStart = face; rightMargin = edge; marginEnd = edge
        topMargin = edge; bottomMargin = edge
    }
    left.requestLayout()
    right.requestLayout()
}

/** Edit profile / settings list (昵称 / 头像 / 性别 / 生日 cards). */
@Mixin
class SelfEditProfileMargins : SelfEditProfileFragment() {
    override fun Y(p0: LayoutInflater, p1: ViewGroup?, p2: Bundle?): View {
        val root = super.Y(p0, p1, p2)!!
        if (Settings.useM3Settings.value) {
            rebuildSettingList(root, "修改资料", "edit_item_container", swipeBack = true)?.let { return it }
        }
        (root as? ViewGroup)?.normalizeListCards()
        Utils.log("CardMarginUnify: edit profile normalized")
        return root
    }
}

/** Change-avatar page (头像: avatar + 相册 / 拍照 buttons). */
@Mixin
class EditAvatarMargins : EditAvatarFragment() {
    override fun Y(p0: LayoutInflater, p1: ViewGroup?, p2: Bundle?): View {
        val root = super.Y(p0, p1, p2)!!
        if (Settings.useM3Settings.value) {
            rebuildAvatarPage(root)?.let { return it }
        }
        (root as? ViewGroup)?.let { rootVg ->
            val buttons = ArrayList<View>()
            rootVg.forEachAll { if (it.javaClass.simpleName == "WatchButton") buttons.add(it) }
            if (buttons.size >= 2) fixSideBySide(buttons[0], buttons[1])
        }
        Utils.log("CardMarginUnify: edit avatar normalized")
        return root
    }
}

/** Input-method picker (打字 / 语音 cards). */
@Mixin
class ChooseInputMargins : ChooseInputFragment() {
    override fun Y(p0: LayoutInflater, p1: ViewGroup?, p2: Bundle?): View {
        val root = super.Y(p0, p1, p2)!!
        (root as? ViewGroup)?.normalizeListCards()
        Utils.log("CardMarginUnify: input picker normalized")
        return root
    }
}

/**
 * Long-press选择弹窗 (conversation long-press: 删除 / 置顶 / 免打扰, and similar AbsItem lists).
 * Hosts `item_setting_with_switch` cards (4dp XML margins) in `setting_container`; normalize them.
 * Uses the real `onCreateView` (not obfuscated here) since this is an AndroidX DialogFragment.
 */
@Mixin
// Constructor is never invoked at runtime (ApkMixin copies methods, not constructors); the args
// here only need to satisfy the super constructor's signature so this compiles.
class SelectDialogMargins :
    SelectDialogFragment(emptyList<Any?>(), {}, "", 0) {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val root = super.onCreateView(inflater, container, savedInstanceState)
        if (Settings.materialChatList.value && root != null) {
            rebuildSettingList(root, "", "setting_container", dismissTarget = this, syntheticToggles = true)
                ?.let { return it }
        }
        (root as? ViewGroup)?.normalizeListCards()
        Utils.log("CardMarginUnify: select dialog normalized")
        return root
    }
}

/**
 * Fullscreen image viewer long-press menu (大图查看长按: 保存到相册/添加到收藏/转发).
 * Same AndroidX DialogFragment shape as [SelectDialogMargins]: `item_setting_with_switch` cards
 * (4dp XML margins) in `setting_container`; normalize them to the unified card margin.
 */
@Mixin
// Constructor is never invoked at runtime (ApkMixin copies methods, not constructors); the args
// here only need to satisfy the super constructor's signature so this compiles.
class MediaBrowserLongClickMenuMargins :
    MediaBrowserLongClickMenuFragment({}, "", 0) {
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        // super here is redirected to the original onCreateView by ApkMixin, which always
        // builds and returns the menu view (non-null); typed nullable only at compile time.
        val root = super.onCreateView(inflater, container, savedInstanceState)!!
        (root as? ViewGroup)?.normalizeListCards()
        Utils.log("CardMarginUnify: media browser long-click menu normalized")
        return root
    }
}

/** Friend settings (好友聊天设置: 置顶会话/清空消息/免打扰): switch-option cards. */
@Mixin
class FriendSettingMargins : FriendSettingFragment() {
    override fun Y(p0: LayoutInflater, p1: ViewGroup?, p2: Bundle?): View {
        val root = super.Y(p0, p1, p2)!!
        if (Settings.useM3Settings.value) {
            rebuildSettingList(root, "聊天设置", swipeBack = true)?.let { return it }
        }
        (root as? ViewGroup)?.normalizeListCards()
        Utils.log("CardMarginUnify: friend settings normalized")
        return root
    }

    // Inject the per-chat "翻译全部消息" switch. Done in onViewCreated (after Y's M3 rebuild has wired
    // up its late-injection harvester) so the added native switch row is picked up into the M3 list.
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (Settings.translateShowAllSwitch.value) TranslateAll.inject(view)
    }
}

/** Group settings (群聊设置): switch-option cards. */
@Mixin
class GroupSettingMargins : TroopSettingFragment() {
    override fun Y(p0: LayoutInflater, p1: ViewGroup?, p2: Bundle?): View {
        val root = super.Y(p0, p1, p2)!!
        if (Settings.useM3Settings.value) {
            rebuildSettingList(root, "群聊设置", swipeBack = true, groupMute = true)?.let { return it }
        }
        (root as? ViewGroup)?.normalizeListCards()
        Utils.log("CardMarginUnify: group settings normalized")
        return root
    }

    // Inject the per-chat "翻译全部消息" switch (see FriendSettingMargins.onViewCreated).
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (Settings.translateShowAllSwitch.value) TranslateAll.inject(view)
    }
}

/** Group member list (查看群成员): rows are inflated lazily into a RecyclerView. */
@Mixin
class GroupMemberMargins : GroupMemberFragment() {
    override fun Y(p0: LayoutInflater, p1: ViewGroup?, p2: Bundle?): View {
        val root = super.Y(p0, p1, p2)!!
        val list = (root as? ViewGroup)?.findAll { it is RecyclerView } as? RecyclerView
        list?.onChildAttached { it.cardMargin() }
        Utils.log("CardMarginUnify: group member list hooked")
        return root
    }
}
