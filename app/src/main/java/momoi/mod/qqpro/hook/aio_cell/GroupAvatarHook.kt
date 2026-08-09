package momoi.mod.qqpro.hook.aio_cell

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextPaint
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.view.Gravity
import android.view.View
import android.widget.TextView
import androidx.core.text.buildSpannedString
import androidx.core.text.inSpans
import com.tencent.qqnt.kernel.nativeinterface.MemberInfo
import com.tencent.qqnt.kernel.nativeinterface.MemberRole
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import com.tencent.watch.aio_impl.ui.widget.AIOCellGroupWidget
import download
import momoi.mod.qqpro.Colors
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.child
import momoi.mod.qqpro.hook.action.SelfContact
import momoi.mod.qqpro.lib.RadiusBackgroundSpan
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.util.Utils
import java.util.WeakHashMap
import kotlin.concurrent.thread

private const val TYPE_OWNER = 1
private const val TYPE_ADMIN = 2
private const val TYPE_SPECIAL = 3
private const val TYPE_NORMAL = 0

fun MemberInfo.displayName(): String = when {
    cardName.isNotEmpty() -> cardName
    remark.isNotEmpty() -> remark
    else -> nick
}

private fun MemberInfo.memberType() = when {
    role == MemberRole.OWNER -> TYPE_OWNER
    role == MemberRole.ADMIN -> TYPE_ADMIN
    !memberSpecialTitle.isNullOrEmpty() -> TYPE_SPECIAL
    else -> TYPE_NORMAL
}

fun MemberInfo.levelTagSpan(): CharSequence {
    val type = memberType()
    // The bulk member list doesn't carry memberLevel; it's only populated when the per-member
    // detail query ran (see CurrentGroupMembers + Settings.showMemberLevel). When enabled and
    // present, prepend the LV<n> badge; the role / special-title suffix is independent.
    val showLevel = Settings.showMemberLevel.value && memberLevel > 0
    val role = when {
        !memberSpecialTitle.isNullOrEmpty() -> memberSpecialTitle!!
        type == TYPE_OWNER -> "群主"
        type == TYPE_ADMIN -> "管理员"
        else -> null
    }
    val label = when {
        showLevel && role != null -> "LV$memberLevel $role"
        showLevel -> "LV$memberLevel"
        role != null -> role
        else -> return ""
    }
    return buildSpannedString {
        inSpans(
            RadiusBackgroundSpan(
                bgColor = when (type) {
                    TYPE_ADMIN -> Colors.NickTag.adminBg
                    TYPE_OWNER -> Colors.NickTag.ownerBg
                    TYPE_SPECIAL -> Colors.NickTag.specialBg
                    else -> Colors.NickTag.normalBg
                },
                textColor = when (type) {
                    TYPE_ADMIN -> Colors.NickTag.adminText
                    TYPE_OWNER -> Colors.NickTag.ownerText
                    TYPE_SPECIAL -> Colors.NickTag.specialText
                    else -> Colors.NickTag.normalText
                }
            ),
            RelativeSizeSpan(0.8f)
        ) {
            append(label)
        }
    }
}

/** Single-line "name + role tag" ([name] kept verbatim; tag floats to the trailing edge). */
fun MemberInfo.oneLineNick(name: CharSequence): CharSequence {
    val isSelf = uid == SelfContact.peerUid
    val tag = levelTagSpan()
    if (tag.isEmpty()) return name
    return buildSpannedString {
        if (isSelf) {
            append(name); append(" "); append(tag)
        } else {
            append(tag); append(" "); append(name)
        }
    }
}

/**
 * Two-line nick text shown beside the avatar:
 *  - no tag: just the (possibly long) [name], free to wrap onto both lines;
 *  - with tag: the name on a single (ellipsized) line, the role tag on the line below.
 *
 * The name must be ellipsized to one line ourselves: a single TextView with "name\ntag" + maxLines=2
 * would otherwise let a long name wrap across both lines and push the tag off (clipped). [avail] is
 * the usable text width (0 = unknown yet → don't ellipsize, caller re-runs after layout).
 */
fun MemberInfo.twoLineNick(name: CharSequence, namePaint: TextPaint, avail: Int): CharSequence {
    val tag = levelTagSpan()
    if (tag.isEmpty()) return name // no tag → let the name use up to maxLines (2)
    val nameLine = if (avail > 0)
        TextUtils.ellipsize(name, namePaint, avail.toFloat(), TextUtils.TruncateAt.END)
    else name
    return buildSpannedString {
        append(nameLine)
        append("\n")
        append(tag)
    }
}

object GroupAvatarHook {
    // Re-download a cached avatar once it's older than this, so avatar changes
    // eventually show up instead of being pinned to the first-ever download.
    private const val AVATAR_CACHE_TTL_MS = 6L * 60 * 60 * 1000 // 6 hours

    private val mainHandler = Handler(Looper.getMainLooper())
    private val avatarBitmaps = HashMap<Long, Bitmap>()
    private val pendingCallbacks = HashMap<Long, ArrayDeque<() -> Unit>>()
    private val widgetCurrentUin = WeakHashMap<AIOCellGroupWidget, Long>()
    private val nickViewCurrentUin = WeakHashMap<TextView, Long>()

    /**
     * Bind (or clear) an avatar drawable on an arbitrary nick [TextView] keyed purely by [uin] —
     * used outside the live group cell (e.g. the merged-forward history viewer), where there is no
     * [AIOCellGroupWidget]. Reuses the same in-memory + on-disk cache and download path as the group
     * cell. [placeEnd] puts the drawable on the trailing edge (self/right-aligned); the default keeps
     * it on the leading edge. The view is tracked so a recycled row never keeps a previous sender's
     * avatar while the new one downloads.
     */
    fun bindAvatarToNick(nickView: TextView, uin: Long, placeEnd: Boolean = false) {
        nickViewCurrentUin[nickView] = uin
        val bitmap = avatarBitmaps[uin]
        if (bitmap != null) {
            applyAvatar(nickView, bitmap, placeEnd)
        } else {
            nickView.setCompoundDrawables(null, null, null, null)
            val needsDownload = !pendingCallbacks.containsKey(uin)
            pendingCallbacks.getOrPut(uin) { ArrayDeque() }.addLast {
                if (nickViewCurrentUin[nickView] == uin) {
                    applyAvatar(nickView, avatarBitmaps[uin]!!, placeEnd)
                }
            }
            if (needsDownload) {
                loadAvatarBitmap(uin) { bmp ->
                    avatarBitmaps[uin] = bmp
                    pendingCallbacks.remove(uin)?.forEach { it() }
                }
            }
        }
    }

    /** Clear any avatar drawable previously bound by [bindAvatarToNick] and stop tracking the view. */
    fun clearNickAvatar(nickView: TextView) {
        nickViewCurrentUin.remove(nickView)
        nickView.setCompoundDrawables(null, null, null, null)
    }

    /**
     * Apply (or clear) the avatar drawable on a cell's nick view. Depends ONLY on the message
     * record (sender uin/uid) + settings — never on the async group-member lookup, which silently
     * drops self and members missing from the (possibly partial) member list. Call this directly
     * and unconditionally on every (non-collapsed) bind so a recycled cell never keeps the previous
     * sender's avatar, and self / unknown-member avatars still show.
     *
     * The nick *text* is handled separately by [bindNick] once the member info is available.
     */
    fun bindAvatar(widget: AIOCellGroupWidget, record: MsgRecord) {
        val nickView = widget.getNickWidget<TextView>() ?: return
        val isSelf = record.senderUid == SelfContact.peerUid
        if (Settings.showGroupAvatar.value && (!isSelf || Settings.showSelfAvatar.value)) {
            val uin = record.senderUin
            widgetCurrentUin[widget] = uin
            val bitmap = avatarBitmaps[uin]
            if (bitmap != null) {
                applyAvatar(nickView, bitmap, isSelf)
            } else {
                // Clear immediately so the recycled cell doesn't show the previous avatar
                // while the new one downloads.
                nickView.setCompoundDrawables(null, null, null, null)
                val needsDownload = !pendingCallbacks.containsKey(uin)
                pendingCallbacks.getOrPut(uin) { ArrayDeque() }.addLast {
                    if (widgetCurrentUin[widget] == uin) {
                        widget.getNickWidget<TextView>()?.let { nv ->
                            applyAvatar(nv, avatarBitmaps[uin]!!, isSelf)
                        }
                    }
                }
                if (needsDownload) {
                    loadAvatarBitmap(uin) { bmp ->
                        avatarBitmaps[uin] = bmp
                        pendingCallbacks.remove(uin)?.forEach { it() }
                    }
                }
            }
        } else {
            widgetCurrentUin.remove(widget)
            nickView.setCompoundDrawables(null, null, null, null)
            nickView.setPaddingRelative(nickView.paddingStart, 0, nickView.paddingEnd, nickView.paddingBottom)
        }
    }

    /**
     * Set the nick text (and its layout: two-line + end-aligned when an avatar is shown). Requires
     * member info, so it runs from the group-member callback. The avatar itself is already handled
     * by [bindAvatar]; this only touches text/alignment/padding.
     */
    fun bindNick(widget: AIOCellGroupWidget, record: MsgRecord, member: MemberInfo) {
        val nickView = widget.getNickWidget<TextView>() ?: return
        // The native nick text is white (for the dark theme) → invisible on a light bubble in light
        // mode. Theme it to onSurface (white in dark, dark in light). The LV/role tag keeps its own
        // span color, so only the name text is affected.
        nickView.setTextColor(Colors.onSurface)
        val isSelf = record.senderUid == SelfContact.peerUid
        // The display name. Always resolve from the member (群名片/备注/昵称) — never read it back
        // from nickView.text: on a recycled cell that text is OUR OWN previously-decorated output
        // ("name\nLV…"), and re-decorating it nests the tag and doubles the ellipsis. displayName()
        // is the native group-card text anyway.
        val name: CharSequence = member.displayName()
        val showAvatar = Settings.showGroupAvatar.value && (!isSelf || Settings.showSelfAvatar.value)
        if (showAvatar) {
            nickView.maxLines = 2
            nickView.ellipsize = TextUtils.TruncateAt.END
            // Self messages are right-aligned, so anchor avatar + nick to the end.
            nickView.gravity = if (isSelf) Gravity.END else Gravity.START
            // The tag (when present, e.g. level enabled) always occupies line 2, so the name is
            // always a single line — ellipsize it to the usable width. Compute that width
            // DETERMINISTICALLY from the avatar size we will reserve, NOT from compoundPaddingLeft:
            // the avatar drawable is attached asynchronously by bindAvatar, so compoundPaddingLeft
            // is 0 (no truncation) or full (truncation) depending purely on download timing — that
            // race was truncating short names like "悠奕" → "悠…" only when the avatar was cached.
            val avatarReserve = (nickView.textSize * Settings.avatarSizeScale.value).toInt() + 4.dp
            val applyText = {
                // Base the usable width on the CELL/row, never on nickView.width: the nick view is
                // wrap_content, so its own width reflects the PREVIOUS (recycled, often shorter)
                // text. Using it ellipsized long names early and inconsistently — a UI dump showed
                // the SAME name truncated to 303px in one cell and 261px in another while ~420px
                // was free. The parent cell spans the row (stable basis); fall back to the screen
                // width when it isn't laid out yet.
                val parentW = (nickView.parent as? View)?.width ?: 0
                val basis = if (parentW > 0) parentW
                    else Utils.application.resources.displayMetrics.widthPixels
                // Leave a small trailing gap so a long name never kisses the right edge.
                val avail = basis - nickView.paddingLeft - nickView.paddingRight - avatarReserve - 8.dp
                nickView.text = member.twoLineNick(name, nickView.paint, avail)
            }
            applyText()
            // Re-run once the real cell width is known (first layout after a fresh bind), so a
            // zero/stale basis never sticks.
            if ((nickView.parent as? View)?.width ?: 0 == 0) nickView.post(applyText)
        } else {
            nickView.maxLines = 1
            nickView.gravity = Gravity.START
            nickView.setPaddingRelative(nickView.paddingStart, 0, nickView.paddingEnd, nickView.paddingBottom)
            nickView.text = member.oneLineNick(name)
        }
    }

    // compound drawable sits beside all text rows, vertically centred. For others
    // it's on the left, for self (right-aligned bubble) it's on the right:
    //   [avatar] [LV badge]        or        [LV badge] [avatar]
    //            display name                 display name
    private fun applyAvatar(nickView: TextView, bitmap: Bitmap, isSelf: Boolean) {
        val avatarSize = (nickView.textSize * Settings.avatarSizeScale.value).toInt()
        val drawable = BitmapDrawable(Utils.application.resources, bitmap).apply {
            setBounds(0, 0, avatarSize, avatarSize)
        }
        if (isSelf) {
            nickView.setCompoundDrawables(null, null, drawable, null)
        } else {
            nickView.setCompoundDrawables(drawable, null, null, null)
        }
        nickView.compoundDrawablePadding = 4.dp
        nickView.setPaddingRelative(nickView.paddingStart, 4.dp, nickView.paddingEnd, nickView.paddingBottom)
    }

    /**
     * Drop every cached avatar — the in-memory bitmaps and the on-disk `avatar_*.jpg`
     * files — so the next time each chat is opened the avatars are re-downloaded fresh.
     * Returns how many cache files were deleted.
     */
    fun clearAvatarCache(): Int {
        avatarBitmaps.clear()
        pendingCallbacks.clear()
        val cacheDir = Utils.application.externalCacheDir
            ?: Utils.application.cacheDir
            ?: Utils.application.filesDir
            ?: return 0
        var count = 0
        cacheDir.listFiles()?.forEach { f ->
            if (f.name.startsWith("avatar_") && f.name.endsWith(".jpg") && f.delete()) {
                count++
            }
        }
        Utils.log("GroupAvatarHook: cleared $count cached avatar files")
        return count
    }

    private fun loadAvatarBitmap(uin: Long, callback: (Bitmap) -> Unit) {
        val cacheDir = Utils.application.externalCacheDir
            ?: Utils.application.cacheDir
            ?: Utils.application.filesDir
        if (cacheDir == null) {
            Utils.log("GroupAvatarHook: no cache dir available, skip avatar uin=$uin")
            return
        }
        val cacheFile = cacheDir.child("avatar_$uin.jpg")
        val url = "https://q.qlogo.cn/headimg_dl?dst_uin=$uin&spec=100"
        Utils.log("GroupAvatarHook: loading avatar uin=$uin")
        val fresh = cacheFile.exists() &&
            (System.currentTimeMillis() - cacheFile.lastModified()) < AVATAR_CACHE_TTL_MS
        if (fresh) {
            thread {
                decodeCircleBitmap(cacheFile.absolutePath)?.let { bmp ->
                    mainHandler.post { callback(bmp) }
                }
            }
        } else {
            // Cache missing or stale: re-download. download() only overwrites the
            // file on HTTP 200, so on failure we still have the old copy to show.
            download(url, cacheFile) { success ->
                if (success || cacheFile.exists()) {
                    if (!success) Utils.log("GroupAvatarHook: refresh failed, using cached avatar uin=$uin")
                    decodeCircleBitmap(cacheFile.absolutePath)?.let { bmp ->
                        mainHandler.post { callback(bmp) }
                    }
                } else {
                    Utils.log("GroupAvatarHook: failed to download avatar uin=$uin")
                }
            }
        }
    }

    private fun decodeCircleBitmap(path: String): Bitmap? {
        val raw = BitmapFactory.decodeFile(path) ?: return null
        val size = minOf(raw.width, raw.height)
        val output = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        canvas.drawCircle(size / 2f, size / 2f, size / 2f, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(raw, 0f, 0f, paint)
        raw.recycle()
        return output
    }
}
