package momoi.mod.qqpro.hook.contact

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.RecyclerView
import com.tencent.qqnt.kernel.nativeinterface.ReqType
import com.tencent.qqnt.msg.KernelServiceUtil
import com.tencent.qqnt.watch.add.QQAddFriendFragment
import com.tencent.qqnt.watch.add.result.FriendDetailData
import com.tencent.qqnt.watch.contact.api.IContactRuntimeService
import com.tencent.qqnt.watch.add.result.QQSearchFriendFragment
import com.tencent.qqnt.watch.add.result.QQSearchResultListFragment
import com.tencent.qqnt.watch.mainframe.SelectFragment
import com.tencent.qqnt.watch.notify.ui.ContactNotifyDetailFragment
import com.tencent.qqnt.watch.notify.ui.ContactNotifyFragment
import com.tencent.qqnt.watch.troop.ui.notification.GroupNotificationFragment
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.hook.EXTRA_SEARCH_PREFILL
import momoi.mod.qqpro.hook.ProfileDetailCard
import momoi.mod.qqpro.hook.openUserQzone
import momoi.mod.qqpro.findAll
import momoi.mod.qqpro.forEachAll
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3Button
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.lib.material.leadingSymbol
import momoi.mod.qqpro.lib.onChildAttached
import momoi.mod.qqpro.util.Utils
import mqq.app.MobileQQ

// ── shared helpers ───────────────────────────────────────────────────────────

private fun idNameOf(v: View): String? = runCatching {
    if (v.id == View.NO_ID) null else v.resources.getResourceEntryName(v.id)
}.getOrNull()

/** setTextColor on a TextView, or a custom widget (e.g. SingleLineTextView) via reflection. */
private fun setTextColorAny(v: View, color: Int) {
    if (v is TextView) { v.setTextColor(color); return }
    runCatching { v.javaClass.getMethod("setTextColor", Int::class.javaPrimitiveType).invoke(v, color) }
}

/** Map a labelled view to its M3 text role, or null when it isn't a known label. */
private fun roleColorFor(id: String?): Int? = when (id) {
    "title", "nickname", "name" -> M3.onSurface
    "sub_title", "subtitle", "uin", "content", "desc", "tips" -> M3.onSurfaceVariant
    // The group-notification "who handled it" line is hardcoded to the QUI blue link color in the
    // native layout (@color/qui_common_text_link) — re-tint it to the M3 accent so it follows the theme.
    "operation_user" -> M3.primary
    else -> null
}

/** Recolor every labelled text view under [view] to the matching M3 role. */
private fun recolorRowText(view: View) {
    (view as? ViewGroup)?.forEachAll { v -> roleColorFor(idNameOf(v))?.let { setTextColorAny(v, it) } }
    if (view is TextView || view.javaClass.simpleName == "SingleLineTextView") {
        roleColorFor(idNameOf(view))?.let { setTextColorAny(view, it) }
    }
}

private fun cardBg(): android.graphics.drawable.Drawable = M3.ripple(M3.rounded(M3.surfaceContainer, M3.radiusLg))

/**
 * Inline "TA的空间" action button (same shape as the rich profile page's QZone button): a full-width
 * filled M3 pill with a leading star, opening [uin]'s own QZone home via [openUserQzone]. Added to the
 * per-user pages (search result card, friend-request detail) below their other action buttons. Returns
 * null when [uin] isn't a valid QQ number (nothing to open).
 */
private fun qzoneButton(ctx: Context, uin: Long): View =
    M3Button(ctx).variant(M3Button.Variant.FILLED).apply {
        text = "TA的空间"
        leadingSymbol(MaterialSymbols.star, M3.onPrimary, sizeDp = 18, gap = 6)
        setOnClickListener { openUserQzone(it, uin) }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 40.dp)
            .apply { topMargin = 10.dp }
    }

/**
 * Materialize a contacts-style list screen: M3.surface page background, surface-container row cards,
 * M3 text colors. Works for both RecyclerView screens (per-row on attach) and statically-inflated row
 * lists (SelectFragment). Gated by [Settings.materialContactsList].
 */
fun styleListRoot(root: View) {
    if (!Settings.materialContactsList.value) return
    runCatching {
        root.setBackgroundColor(M3.surface)
        val rv = (root as? ViewGroup)?.findAll { it is RecyclerView } as? RecyclerView
        if (rv != null) {
            rv.setBackgroundColor(M3.surface)
            rv.onChildAttached { row ->
                row.background = cardBg()
                recolorRowText(row)
            }
        } else (root as? ViewGroup)?.post {
            // Statically inflated rows (each a backgrounded child of a LinearLayout) → M3 cards.
            root.forEachAll { v ->
                if (v !== root && v.background != null && v.parent is LinearLayout) v.background = cardBg()
            }
            recolorRowText(root)
        }
    }.onFailure { Utils.log("styleListRoot: $it") }
}

// ── friend / group notification list screens ─────────────────────────────────

@Mixin
class ContactNotifyMaterial : ContactNotifyFragment() {
    override fun Y(p0: LayoutInflater, p1: ViewGroup?, p2: Bundle?): View {
        val root = super.Y(p0, p1, p2)!!
        styleListRoot(root)
        return root
    }
}

@Mixin
class GroupNotificationMaterial : GroupNotificationFragment() {
    override fun Y(p0: LayoutInflater, p1: ViewGroup?, p2: Bundle?): View {
        val root = super.Y(p0, p1, p2)!!
        styleListRoot(root)
        return root
    }
}

// ── friend request confirm/deny detail page ──────────────────────────────────
// Built from scratch in Y() (runs once, WatchFragment caches the result).
// Layout: M3 surface + avatar card + name/tips/uin text + optional profile rows + two M3 buttons.
// The native ConstraintLayout is kept hidden so c0() can still update its TextViews; onViewCreated
// syncs the populated text from native views to ours after super.onViewCreated() calls c0().

private const val TAG_ND_NATIVE = "qqpro_nd_native"
private const val TAG_ND_NICK   = "qqpro_nd_nick"
private const val TAG_ND_TIPS   = "qqpro_nd_tips"
private const val TAG_ND_UIN    = "qqpro_nd_uin"
private const val TAG_ND_ROWS   = "qqpro_nd_rows"
private const val TAG_ND_DIV    = "qqpro_nd_div"
private const val TAG_ND_BTNS   = "qqpro_nd_btns"
private const val TAG_ND_REJECT = "qqpro_nd_reject"
private const val TAG_ND_AGREE  = "qqpro_nd_agree"
private const val TAG_ND_GOTO   = "qqpro_nd_goto"
private const val TAG_ND_QZONE  = "qqpro_nd_qzone"

@Mixin
class ContactNotifyDetailMaterial : ContactNotifyDetailFragment() {
    // W()=true → WatchFragment skips the bgView gradient wrapper so our surface-colored FrameLayout
    // covers the screen cleanly.
    override fun W(): Boolean = Settings.materialContactsList.value || super.W()

    override fun Y(p0: LayoutInflater, p1: ViewGroup?, p2: Bundle?): View {
        val native = super.Y(p0, p1, p2)!!
        if (!Settings.materialContactsList.value) return native
        return runCatching { buildNotifyDetailPage(native as ViewGroup) }
            .onFailure { Utils.log("ContactNotifyDetailMaterial.Y: $it") }
            .getOrDefault(native)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // super calls c0() which populates the native TextViews + wires the native buttons — sync
        // text + decide which M3 buttons to show afterward.
        super.onViewCreated(view, savedInstanceState)
        if (Settings.materialContactsList.value)
            runCatching { syncNotifyDetail(this, view) }
                .onFailure { Utils.log("ContactNotifyDetailMaterial.sync: $it") }
    }
}

private fun buildNotifyDetailPage(native: ViewGroup): View {
    val ctx = native.context
    fun rid(n: String) = ctx.resources.getIdentifier(n, "id", ctx.packageName)

    val avatar    = native.findViewById<View>(rid("avatar"))
    val cancelBtn = native.findViewById<Button>(rid("cancel"))
    val confirmBtn = native.findViewById<Button>(rid("confirm"))

    // Re-parent avatar so the native WatchAvatarView (with its async URL load) shows in our card.
    (avatar?.parent as? ViewGroup)?.removeView(avatar)

    val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = M3.rounded(M3.surfaceContainer, M3.radiusLg)
        setPadding(16.dp, 20.dp, 16.dp, 16.dp)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    avatar?.let {
        it.layoutParams = LinearLayout.LayoutParams(64.dp, 64.dp).apply { gravity = Gravity.CENTER_HORIZONTAL }
        card.addView(it)
    }

    // Placeholder TextViews — text filled in onViewCreated after c0() runs.
    val nickView = TextView(ctx).apply {
        tag = TAG_ND_NICK; textSize = 15f; setTextColor(M3.onSurface)
        gravity = Gravity.CENTER; maxLines = 4; isSingleLine = false
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = 10.dp }
    }
    val uinView = TextView(ctx).apply {
        tag = TAG_ND_UIN; textSize = 11f; setTextColor(M3.onSurfaceVariant); gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = 3.dp }
    }
    val tipsView = TextView(ctx).apply {
        tag = TAG_ND_TIPS; textSize = 12f; setTextColor(M3.onSurfaceVariant); gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = 6.dp }
    }
    card.addView(nickView); card.addView(uinView); card.addView(tipsView)

    val divider = View(ctx).apply {
        tag = TAG_ND_DIV; setBackgroundColor(0x22_FFFFFF); visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = 10.dp }
    }
    val rows = LinearLayout(ctx).apply {
        tag = TAG_ND_ROWS; orientation = LinearLayout.VERTICAL; visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = 4.dp }
    }
    card.addView(divider); card.addView(rows)

    // 拒绝 + 同意 side by side (forward to native cancel/confirm). The row + each button are tagged so
    // syncNotifyDetail can show exactly what the native ReqType decision tree would show.
    val rejectBtn = M3Button(ctx).variant(M3Button.Variant.TONAL).apply {
        tag = TAG_ND_REJECT
        text = "拒绝"
        setOnClickListener { cancelBtn?.performClick() }
        layoutParams = LinearLayout.LayoutParams(0, 40.dp, 1f).apply { rightMargin = 8.dp }
    }
    val agreeBtn = M3Button(ctx).variant(M3Button.Variant.FILLED).apply {
        tag = TAG_ND_AGREE
        text = "同意"
        setOnClickListener { confirmBtn?.performClick() }
        layoutParams = LinearLayout.LayoutParams(0, 40.dp, 1f).apply { leftMargin = 8.dp }
    }
    val btnsRow = LinearLayout(ctx).apply {
        tag = TAG_ND_BTNS; orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = 18.dp }
    }
    btnsRow.addView(rejectBtn); btnsRow.addView(agreeBtn)

    // 去聊天 — shown only for already-added friends (matches the native icon_chat/去聊天 state); forwards
    // to native confirm, which c0() wires to navigate-to-chat for those states.
    val gotoChatBtn = M3Button(ctx).variant(M3Button.Variant.FILLED).apply {
        tag = TAG_ND_GOTO
        text = "去聊天"
        leadingSymbol(MaterialSymbols.chat_bubble, M3.onPrimary, sizeDp = 18, gap = 6)
        setOnClickListener { confirmBtn?.performClick() }
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 40.dp)
            .apply { topMargin = 18.dp }
    }

    val column = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        setPadding(14.dp, 26.dp, 14.dp, 26.dp)
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    // Holder for the TA的空间 button — filled in syncNotifyDetail once the requestor's uid is known.
    val qzoneHolder = LinearLayout(ctx).apply {
        tag = TAG_ND_QZONE; orientation = LinearLayout.VERTICAL
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    column.addView(card); column.addView(btnsRow); column.addView(gotoChatBtn); column.addView(qzoneHolder)

    native.visibility = View.GONE
    native.tag = TAG_ND_NATIVE

    val scroll = ScrollView(ctx).apply {
        isFillViewport = false
        overScrollMode = View.OVER_SCROLL_NEVER
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        addView(column)
    }

    Utils.log("buildNotifyDetailPage: built")
    return FrameLayout(ctx).apply {
        setBackgroundColor(M3.surface)
        addView(native, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(scroll)
    }
}

private fun syncNotifyDetail(frag: ContactNotifyDetailFragment, frame: View) {
    if (frame !is ViewGroup) return
    val native = frame.findViewWithTag<ViewGroup>(TAG_ND_NATIVE) ?: return
    val ctx = frame.context
    fun rid(n: String) = ctx.resources.getIdentifier(n, "id", ctx.packageName)

    val nick = native.findViewById<TextView>(rid("nickname"))?.text?.toString().orEmpty()
    val tips = native.findViewById<TextView>(rid("tips"))?.text?.toString().orEmpty()
    val uin  = native.findViewById<TextView>(rid("uin"))?.text?.toString().orEmpty()

    frame.findViewWithTag<TextView>(TAG_ND_NICK)?.text = nick
    frame.findViewWithTag<TextView>(TAG_ND_TIPS)?.text = tips
    frame.findViewWithTag<TextView>(TAG_ND_UIN)?.text  = uin

    // item (fragment field g): e = uid, f = reqType. The request always carries the requestor's uid,
    // so detail can be fetched even for strangers (uin→uid resolution would fail for non-friends).
    val item = runCatching { frag.g }.getOrNull()
    val uid = runCatching { item?.e }.getOrNull().orEmpty()
    val reqType = runCatching { item?.f }.getOrNull() ?: -1

    // Replicate the native ReqType decision tree (see ContactNotifyDetailFragment.c0):
    //  KPEERINITIATOR              → 拒绝 + 同意   (incoming pending request)
    //  KMEAGREEANYONE              → 同意 only     (someone added you under allow-anyone)
    //  KME/KPEER AGREEDANDADDED    → 去聊天        (now friends) — only when isFriend
    //  everything else             → no buttons    (等待对方验证 / 你已同意 / refused / ignored / …)
    val rejectBtn = frame.findViewWithTag<View>(TAG_ND_REJECT)
    val agreeBtn  = frame.findViewWithTag<View>(TAG_ND_AGREE)
    val btnsRow   = frame.findViewWithTag<View>(TAG_ND_BTNS)
    val gotoBtn   = frame.findViewWithTag<View>(TAG_ND_GOTO)
    when {
        reqType == ReqType.KPEERINITIATOR.ordinal -> {
            rejectBtn?.visibility = View.VISIBLE; agreeBtn?.visibility = View.VISIBLE
            btnsRow?.visibility = View.VISIBLE
        }
        reqType == ReqType.KMEAGREEANYONE.ordinal -> {
            rejectBtn?.visibility = View.GONE; agreeBtn?.visibility = View.VISIBLE
            btnsRow?.visibility = View.VISIBLE
        }
        (reqType == ReqType.KMEAGREEDANDADDED.ordinal ||
            reqType == ReqType.KPEERAGREEDANDADDED.ordinal) && isFriend(uid) -> {
            gotoBtn?.visibility = View.VISIBLE
        }
        // else: leave everything GONE.
    }
    // TA的空间 — resolve this requestor's QQ number (uid→uin via kernel, else from the shown uin text)
    // and add the QZone button once. Idempotent: onViewCreated may run again on back-navigation.
    val qzoneHolder = frame.findViewWithTag<LinearLayout>(TAG_ND_QZONE)
    if (qzoneHolder != null && qzoneHolder.childCount == 0) {
        val qzoneUin = runCatching {
            KernelServiceUtil.f()?.uixConvertService?.y(uid)?.takeIf { it > 0L }
        }.getOrNull() ?: uin.filter { it.isDigit() }.toLongOrNull()?.takeIf { it > 0L }
        if (qzoneUin != null) {
            qzoneHolder.addView(qzoneButton(ctx, qzoneUin))
            Utils.log("syncNotifyDetail: TA的空间 button added (uin=$qzoneUin)")
        }
    }

    Utils.log("syncNotifyDetail: nick='$nick' uin='$uin' uid='$uid' reqType=$reqType")

    // Extended profile info — fetch by the requestor's uid (carried by the request; works for strangers).
    val rows    = frame.findViewWithTag<LinearLayout>(TAG_ND_ROWS) ?: return
    val divider = frame.findViewWithTag<View>(TAG_ND_DIV)
    if (uid.isBlank()) return
    ProfileDetailCard.fetch(uid) { info ->
        rows.post {
            if (info != null && ProfileDetailCard.bindInto(ctx, rows, info) > 0) {
                divider?.visibility = View.VISIBLE; rows.visibility = View.VISIBLE
            }
        }
    }
}

/** Is [uid] an existing friend, per IContactRuntimeService. */
private fun isFriend(uid: String): Boolean = runCatching {
    if (uid.isBlank()) return false
    val app = MobileQQ.sMobileQQ?.peekAppRuntime() ?: return false
    val svc = app.getRuntimeService(IContactRuntimeService::class.java, "")
    svc.isFriend(uid)
}.onFailure { Utils.log("isFriend error: $it") }.getOrDefault(false)

// ── search result host (ViewPager of result cards) ───────────────────────────
// The host shows a CircleIndicator (page dots) above the pager — for a single result that's just a
// lone dot in a strip at the top ("bar on top"). Hide it when there's ≤1 result.

@Mixin
class QQSearchResultListMaterial : QQSearchResultListFragment() {
    override fun Y(p0: LayoutInflater, p1: ViewGroup?, p2: Bundle?): View {
        val root = super.Y(p0, p1, p2)!!
        if (Settings.materialContactsList.value) runCatching {
            val count = runCatching { this.f.size }.getOrDefault(0)
            if (count <= 1) {
                val id = root.resources.getIdentifier("circle_indicator", "id", root.context.packageName)
                if (id != 0) root.findViewById<View>(id)?.visibility = View.GONE
            }
            Utils.log("QQSearchResultListMaterial: results=$count, indicator hidden=${count <= 1}")
        }.onFailure { Utils.log("QQSearchResultListMaterial: $it") }
        return root
    }
}

// ── search result card (after searching a QQ number) ─────────────────────────
// QQSearchResultListFragment is a ViewPager2 of QQSearchFriendFragment cards (avatar / name / QQ + an
// add_friend AppCompatButton that says 加好友 or 去聊天). Rebuilt with the same RichProfile-style card
// as the friend-request detail, and the action button restyled to a pill WITH a leading icon (chat /
// person_add) like the group profile viewer's buttons. The native button is restyled in place so its
// click + post-add text swap keep working.

// Built from scratch in Y() (onCreateView), which WatchFragment runs ONCE and caches — so it survives
// back-navigation with no idempotency tricks. We re-parent the native avatar/name/QQ into our own M3
// card (a one-time move, safe because Y runs once), keep the native tree as a hidden holder so its
// button keeps working, and add our own pill action button with a real ImageView icon.

@Mixin
class QQSearchFriendMaterial : QQSearchFriendFragment(FriendDetailData("", "", "", false, "", false)) {
    override fun Y(p0: LayoutInflater, p1: ViewGroup?, p2: Bundle?): View {
        val native = super.Y(p0, p1, p2)!!
        if (!Settings.materialContactsList.value) return native
        // uid is null from the search factory (uin→uid resolution is async); uin (field b) is always set.
        val uid = runCatching { this.f.c }.getOrNull().orEmpty()
        val uin = runCatching { this.f.b }.getOrNull().orEmpty()
        val isChat = runCatching { this.f.e && !this.f.g }.getOrDefault(false)
        return runCatching { buildSearchCard(native as ViewGroup, uid, uin, isChat) }
            .onFailure { Utils.log("QQSearchFriendMaterial: $it") }
            .getOrDefault(native)
    }
}

private fun buildSearchCard(native: ViewGroup, uid: String, uin: String, isChat: Boolean): View {
    val ctx = native.context
    fun rid(n: String) = ctx.resources.getIdentifier(n, "id", ctx.packageName)
    val avatar = native.findViewById<View>(rid("self_avatar"))
    val nameV = native.findViewById<TextView>(rid("self_name"))
    val qqV = native.findViewById<TextView>(rid("self_qq"))
    val btn = native.findViewById<Button>(rid("add_friend"))

    // Card: same structure as RichProfilePage — avatar → name → QQ → divider → info rows.
    val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        background = M3.rounded(M3.surfaceContainer, M3.radiusLg)
        setPadding(16.dp, 20.dp, 16.dp, 16.dp)
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    avatar?.let {
        (it.parent as? ViewGroup)?.removeView(it)
        it.layoutParams = LinearLayout.LayoutParams(64.dp, 64.dp).apply { gravity = Gravity.CENTER_HORIZONTAL }
        card.addView(it)
    }
    nameV?.let {
        (it.parent as? ViewGroup)?.removeView(it)
        it.setTextColor(M3.onSurface); it.textSize = 15f; it.gravity = Gravity.CENTER
        it.isSingleLine = false; it.maxLines = 4
        it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = 10.dp }
        card.addView(it)
    }
    qqV?.let {
        (it.parent as? ViewGroup)?.removeView(it)
        it.setTextColor(M3.onSurfaceVariant); it.textSize = 11f; it.gravity = Gravity.CENTER
        it.layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = 3.dp }
        card.addView(it)
    }

    // Extended info rows — fetched by uid if available, otherwise resolved uin→uid first.
    val rows = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = 4.dp }
    }
    val divider = View(ctx).apply {
        setBackgroundColor(0x22_FFFFFF); visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1).apply { topMargin = 10.dp }
    }
    card.addView(divider); card.addView(rows)

    fun onInfo(info: ProfileDetailCard.Info?) {
        rows.post {
            if (info != null && ProfileDetailCard.bindInto(ctx, rows, info) > 0) {
                divider.visibility = View.VISIBLE; rows.visibility = View.VISIBLE
            }
        }
    }
    when {
        uid.isNotBlank() -> ProfileDetailCard.fetch(uid, ::onInfo)
        uin.isNotBlank() -> uin.toLongOrNull()?.let { ProfileDetailCard.fetchByUin(it, ::onInfo) }
    }

    // Action button: real M3Button pill matching RichProfilePage style.
    val actionBtn = M3Button(ctx).variant(M3Button.Variant.FILLED).apply {
        text = if (isChat) "去聊天" else "加好友"
        val sym = if (isChat) MaterialSymbols.chat_bubble else MaterialSymbols.person_add
        leadingSymbol(sym, M3.onPrimary, sizeDp = 18, gap = 6)
        setOnClickListener { btn?.performClick() }
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 40.dp)
            .apply { topMargin = 18.dp }
    }

    val column = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(14.dp, 26.dp, 14.dp, 26.dp)
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }
    column.addView(card)
    column.addView(actionBtn)
    // TA的空间 — open this searched user's QZone (same button as the rich profile page).
    uin.trim().toLongOrNull()?.takeIf { it > 0 }?.let { column.addView(qzoneButton(ctx, it)) }

    val scroll = ScrollView(ctx).apply {
        isFillViewport = false
        overScrollMode = View.OVER_SCROLL_NEVER
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        addView(column)
    }

    native.visibility = View.GONE     // keep native (with its button) hidden
    val frame = FrameLayout(ctx).apply {
        setBackgroundColor(M3.surface)
        addView(native, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(scroll)
    }
    Utils.log("buildSearchCard: built (uid=$uid, uin=$uin, chat=$isChat)")
    return frame
}

// ── add menu (面对面 / 搜索号码 / 创建群聊) → custom Material layout ─────────────
// With only three options there's no need for a list: rebuild as a centered column of Material option
// cards with proper Material Symbols icons. The native rows (which carry the real navigation click
// handlers) are kept hidden and driven via performClick(), so navigation is untouched.

@Mixin
class SelectFragmentMaterial : SelectFragment() {
    override fun Y(p0: LayoutInflater, p1: ViewGroup?, p2: Bundle?): View {
        val root = super.Y(p0, p1, p2)!!
        if (Settings.materialContactsList.value)
            runCatching { buildCustomAddMenu(root) }.onFailure { Utils.log("SelectFragmentMaterial: $it") }
        return root
    }
}

fun buildCustomAddMenu(root: View) {
    if (root !is ViewGroup) return
    val ctx = root.context
    val titleId = ctx.resources.getIdentifier("title", "id", ctx.packageName)
    // The native vertical list of select_item rows (each a ConstraintLayout with R.id.title + a click
    // handler). findAll is pre-order, so the outer container LinearLayout is returned first.
    val container = root.findAll { it is LinearLayout } as? LinearLayout ?: return
    val rows = (0 until container.childCount).map { container.getChildAt(it) }.filterIsInstance<ViewGroup>()
    if (rows.isEmpty()) return

    container.visibility = View.GONE
    root.setBackgroundColor(M3.surface)

    val column = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(16.dp, 16.dp, 16.dp, 16.dp)
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }
    for (row in rows) {
        val title = row.findViewById<TextView>(titleId)?.text?.toString().orEmpty()
        column.addView(addOptionCard(ctx, title) { row.performClick() })
    }
    val scroll = ScrollView(ctx).apply {
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(column)
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }
    root.addView(scroll)
    Utils.log("buildCustomAddMenu: ${rows.size} option cards")
}

private fun addOptionCard(ctx: Context, title: String, onClick: () -> Unit): View {
    val iconPath = when {
        title.contains("面对面") || title.contains("碰") || title.contains("knock", true) -> MaterialSymbols.contactless
        title.contains("群") -> MaterialSymbols.group_add
        title.contains("搜") || title.contains("号") || title.contains("查找") -> MaterialSymbols.search
        else -> MaterialSymbols.person_add
    }
    val card = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = M3.ripple(M3.rounded(M3.surfaceContainer, M3.radiusLg))
        setPadding(16.dp, 14.dp, 16.dp, 14.dp)
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = 8.dp }
    }
    card.addView(ImageView(ctx).apply {
        setImageDrawable(MaterialSymbol(iconPath, M3.primary))
        layoutParams = LinearLayout.LayoutParams(24.dp, 24.dp).apply { rightMargin = 14.dp }
    })
    card.addView(TextView(ctx).apply {
        text = title
        textSize = 15f
        setTextColor(M3.onSurface)
        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
    })
    return card
}

// ── add-by-number page → Material field + system numeric IME ──────────────────
// The native page has NO confirm button: a custom on-screen NumericKeyboardView's 确认 key drives the
// search via the fragment's own `curString` (field g) and the keyboard's confirm Function0 (field f.f).
// We rebuild the page with a Material outlined field driven by the system numeric IME, plus an M3 search
// button — and on submit we set `curString` and invoke the native confirm listener, so all native
// search + result-navigation logic is reused unchanged.

@Mixin
class QQAddFriendMaterial : QQAddFriendFragment() {
    override fun Y(p0: LayoutInflater, p1: ViewGroup?, p2: Bundle?): View {
        val native = super.Y(p0, p1, p2)!!
        if (!Settings.materialContactsList.value) return native
        return runCatching { buildAddByNumber(this, native as ViewGroup) }
            .onFailure { Utils.log("QQAddFriendMaterial: $it") }
            .getOrDefault(native)
    }
}

/**
 * Returns a fresh FrameLayout hosting a Material search form. The native [native] ConstraintLayout is
 * kept hidden inside the frame so its `g`/`e`/`f` fragment fields (curString, display TextView, keyboard
 * confirm Function0) remain accessible and drive the real search + navigation logic unchanged.
 *
 * Returning a new view (instead of modifying root in-place) avoids the ConstraintLayout sizing problem:
 * a ScrollView added to a ConstraintLayout without constraint params collapses to 0×0.
 */
fun buildAddByNumber(frag: QQAddFriendFragment, native: ViewGroup): View {
    val ctx = native.context
    native.visibility = View.GONE

    val column = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(20.dp, 20.dp, 20.dp, 20.dp)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }
    column.addView(TextView(ctx).apply {
        text = "搜索账号添加"
        textSize = 16f
        setTextColor(M3.onSurface)
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        ).apply { bottomMargin = 16.dp }
    })

    val edit = EditText(ctx).apply {
        hint = "请输入QQ号"
        setTextColor(M3.onSurface)
        setHintTextColor(M3.hint)
        textSize = 16f
        gravity = Gravity.CENTER
        isSingleLine = true
        inputType = InputType.TYPE_CLASS_NUMBER
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        filters = arrayOf(InputFilter.LengthFilter(11))
        background = M3.outlined(M3.outline, M3.radiusMd).apply { setColor(M3.surfaceContainerHigh) }
        setPadding(14.dp, 12.dp, 14.dp, 12.dp)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
        )
    }
    column.addView(edit)

    val search = M3Button(ctx).variant(M3Button.Variant.FILLED).apply {
        text = "搜索"
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, 44.dp,
        ).apply { topMargin = 16.dp }
    }
    column.addView(search)

    val submit = {
        val txt = edit.text?.toString()?.trim().orEmpty()
        // Sync the native input state, then fire the native confirm listener (validates length, shows
        // the loading dialog, calls searchFriend(), and navigates to the result list).
        frag.g = txt
        (frag.e as? TextView)?.text = txt
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(edit.windowToken, 0)
        runCatching { frag.f.f?.invoke() }.onFailure { Utils.log("QQAddFriendMaterial submit: $it") }
    }
    search.setOnClickListener { submit() }
    edit.setOnEditorActionListener { _, actionId, _ ->
        if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE ||
            actionId == EditorInfo.IME_ACTION_GO) { submit(); true } else false
    }

    // Keep curString (g) synced with the field so typed / pasted / prefilled input all reach the search.
    edit.addTextChangedListener(object : TextWatcher {
        override fun afterTextChanged(s: Editable?) { frag.g = s?.toString().orEmpty().filter { it.isDigit() }.take(11) }
        override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
        override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {}
    })
    // Prefill from the search arg (e.g. tapping a group-invite card → openAddSearch).
    val prefill = frag.arguments?.getString(EXTRA_SEARCH_PREFILL)?.filter { it.isDigit() }?.take(11)
    if (!prefill.isNullOrEmpty()) {
        edit.setText(prefill); edit.setSelection(prefill.length); frag.g = prefill
        Utils.log("QQAddFriendMaterial: prefilled '$prefill'")
    }

    val scroll = ScrollView(ctx).apply {
        isFillViewport = true
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(column)
        layoutParams = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        )
    }

    edit.post {
        edit.requestFocus()
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT)
    }
    Utils.log("QQAddFriendMaterial: Material add-by-number page built")

    return FrameLayout(ctx).apply {
        setBackgroundColor(M3.surface)
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT,
        )
        addView(native, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(scroll)
    }
}
