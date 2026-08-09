package momoi.mod.qqpro.hook.login
import momoi.mod.qqpro.lib.setClipToOutlineCompat

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.tencent.qqnt.account.login.ui.QrLoginFragment
import loadPicUrl
import momoi.mod.qqpro.hook.LoginQrZoomHelper
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3Button
import momoi.mod.qqpro.lib.material.M3ProgressDrawable
import momoi.mod.qqpro.lib.material.MaterialSymbol
import momoi.mod.qqpro.lib.material.MaterialSymbols
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.runOnUi

/**
 * Material 3 rebuild of the QR-code login page (gated by `Settings.materializeLogin`, driven from the
 * [momoi.mod.qqpro.hook.LoginQrZoom] @Mixin on [QrLoginFragment]).
 *
 * Strategy — keep the native login *engine*, rebuild the *chrome*: QQ's `QrLoginFragment` still
 * generates/refreshes the colorful QR (its `LoginQrCode g` renders into the binding's
 * `ColorfulQRWithBackgroundView c`) and fires its state callbacks. We reparent the live QR view into a
 * white card and drive the surrounding M3 UI from the callbacks.
 *
 * Once the QR is scanned (uin captured by [LoginScanObserver]) the QR is no longer useful, so we hide
 * it and show the account avatar + number — far more legible on a watch. On expiry/failure a refresh
 * button is overlaid on top of the QR. All view-building lives here in `momoi.*` (not in the @Mixin
 * body copied into QQ's package).
 */
object LoginM3 {
    private fun avatarUrl(uin: String) = "https://q1.qlogo.cn/g?b=qq&nk=$uin&s=160"

    @Volatile private var loginFrag: QrLoginFragment? = null
    @Volatile private var qrView: View? = null
    @Volatile private var bigAvatar: ImageView? = null
    @Volatile private var nameText: TextView? = null
    @Volatile private var refreshOverlay: View? = null
    @Volatile private var statusText: TextView? = null
    @Volatile private var spinner: ImageView? = null
    // uin learned at the SCANNED stage (ret 53), before phone-confirm. Null until a scan happens.
    @Volatile private var scannedUin: String? = null
    // uin whose avatar is currently loaded into bigAvatar. The login poll re-fires the scanned/confirmed
    // callbacks every few seconds while waiting for phone confirmation; without this guard every call
    // cleared the photo (→ gray placeholder) and re-downloaded it, so the avatar flickered gray→photo.
    @Volatile private var loadedAvatarUin: String? = null

    /** Called by [LoginScanObserver] when the QR is scanned — stash the account uin for the UI. */
    fun captureScannedUin(uin: String?) {
        scannedUin = uin?.trim()?.takeIf { it.isNotEmpty() }
        if (scannedUin != null) onScanned()
    }

    fun clear() {
        loginFrag = null; qrView = null; bigAvatar = null; nameText = null
        refreshOverlay = null; statusText = null; spinner = null; scannedUin = null; loadedAvatarUin = null
    }

    fun build(frag: QrLoginFragment, nativeRoot: View?): View? {
        val root = nativeRoot ?: return null
        try {
            clear()
            val ctx = root.context
            val binding = frag.f ?: run {
                Utils.log("LoginM3: no binding, keep native"); LoginQrZoomHelper.attach(root); return root
            }
            val qr: View = binding.c
            val phoneBtn: View? = binding.b
            (qr.parent as? ViewGroup)?.removeView(qr)

            val column = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(16.dp, 14.dp, 16.dp, 14.dp)
            }

            // Brand row
            val brand = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            val iconId = ctx.resources.getIdentifier("icon", "drawable", ctx.packageName)
            if (iconId != 0) brand.addView(ImageView(ctx).apply { runCatching { setImageDrawable(ctx.getDrawable(iconId)) } },
                LinearLayout.LayoutParams(24.dp, 24.dp).apply { rightMargin = 8.dp })
            brand.addView(TextView(ctx).apply {
                text = "QQ Max"; setTextColor(M3.onSurface); textSize = 19f
                typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            })
            column.addView(brand, wrap().apply { bottomMargin = 14.dp })

            // QR card: holds the live QR, a big avatar (shown after scan), and a refresh overlay.
            val dm = ctx.resources.displayMetrics
            val side = (minOf(dm.widthPixels, dm.heightPixels) * 0.62f).toInt().coerceAtLeast(120.dp)
            val card = FrameLayout(ctx).apply {
                // Themed frame (follows the M3 / system palette); the QR sits on its own white chip
                // below so it keeps the light quiet-zone a scanner needs regardless of theme.
                background = GradientDrawable().apply { setColor(M3.surfaceContainerHigh); cornerRadius = M3.radiusLg }
                val p = 8.dp; setPadding(p, p, p, p)
            }
            // White chip directly behind the live QR — guarantees scannability on any theme. The QR
            // module color itself follows the theme via the QrCodeThemeColor @Mixin on b().
            qr.background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = M3.radiusMd }
            card.addView(qr, FrameLayout.LayoutParams(side, side, Gravity.CENTER))
            runCatching { LoginQrZoomHelper.attachTapZoom(qr) }

            val avatar = ImageView(ctx).apply {
                visibility = View.GONE
                scaleType = ImageView.ScaleType.CENTER_CROP
                setClipToOutlineCompat(true)
            }
            val avSide = (side * 0.7f).toInt()
            card.addView(avatar, FrameLayout.LayoutParams(avSide, avSide, Gravity.CENTER))

            // Refresh overlay (covers the card; shown on expiry/failure).
            val overlay = FrameLayout(ctx).apply {
                visibility = View.GONE
                // Same corner radius as the QR chip below (radiusMd) so the timeout scrim's rounded
                // corners line up with the QR instead of showing a larger radius.
                background = GradientDrawable().apply {
                    setColor((M3.surfaceContainerHigh and 0x00FFFFFF) or 0xE6_000000.toInt()); cornerRadius = M3.radiusMd
                }
                isClickable = true
            }
            overlay.addView(M3Button(ctx).apply {
                text = "刷新二维码"; variant(M3Button.Variant.TONAL)
                setOnClickListener {
                    runCatching { loginFrag?.g?.b() }.onFailure { Utils.log("LoginM3: refresh failed: $it") }
                }
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            card.addView(overlay, FrameLayout.LayoutParams(side, side, Gravity.CENTER))

            column.addView(card, wrap())

            // Account number line (shown with the avatar after scan).
            val name = TextView(ctx).apply {
                visibility = View.GONE; setTextColor(M3.onSurface); textSize = 16f
                typeface = android.graphics.Typeface.DEFAULT_BOLD; gravity = Gravity.CENTER
            }
            column.addView(name, wrap().apply { topMargin = 10.dp })

            // Status row: spinner + text.
            val statusRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
            val sp = ImageView(ctx).apply {
                setImageDrawable(M3ProgressDrawable(M3.primary, 2.5f * dm.density)); visibility = View.GONE
            }
            statusRow.addView(sp, LinearLayout.LayoutParams(16.dp, 16.dp).apply { rightMargin = 6.dp })
            val status = TextView(ctx).apply {
                text = "扫描二维码登录"; setTextColor(M3.onSurfaceVariant); textSize = 13f; gravity = Gravity.CENTER
            }
            statusRow.addView(status, wrap())
            column.addView(statusRow, wrap().apply { topMargin = 12.dp })

            // Phone-number login → delegate to the native button.
            if (phoneBtn != null) column.addView(M3Button(ctx).apply {
                text = "手机号登录"; variant(M3Button.Variant.TEXT)
                setOnClickListener { runCatching { phoneBtn.performClick() } }
            }, wrap().apply { topMargin = 4.dp })

            loginFrag = frag; qrView = qr; bigAvatar = avatar; nameText = name
            refreshOverlay = overlay; statusText = status; spinner = sp
            Utils.log("LoginM3: built M3 login page (qr=$side)")
            return ScrollView(ctx).apply {
                isFillViewport = true; setBackgroundColor(M3.surface)
                addView(column, FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER })
            }
        } catch (e: Throwable) {
            Utils.log("LoginM3: build failed (${e.javaClass.simpleName}: ${e.message}), keep native")
            clear(); runCatching { LoginQrZoomHelper.attach(root) }; return root
        }
    }

    // ── State transitions (from the QrLoginFragment callbacks) ───────────────────────────────────

    /** Fresh QR available — restore the QR, drop any captured identity. */
    fun onQrReady() {
        scannedUin = null
        loadedAvatarUin = null
        update {
            qrView?.visibility = View.VISIBLE
            bigAvatar?.visibility = View.GONE
            refreshOverlay?.visibility = View.GONE
            nameText?.visibility = View.GONE
            status("扫描二维码登录", M3.onSurfaceVariant); spinnerVisible(false)
        }
    }

    fun onLoading() = update {
        refreshOverlay?.visibility = View.GONE
        status("加载中…", M3.onSurfaceVariant); spinnerVisible(true)
    }

    /** Scanned (waiting for phone confirm). Replace the QR with the account avatar + number. */
    fun onScanned() = showIdentity(scannedUin, "已扫描，请在手机上确认", loading = false)

    /** Confirmed on phone — logging in. */
    fun onConfirmed(uin: String?) =
        showIdentity(uin?.trim()?.takeIf { it.isNotEmpty() } ?: scannedUin, "登录中…", loading = true)

    fun onExpired() = showRefresh("二维码已过期，点击刷新")

    fun onError(msg: String?) = showRefresh(if (msg.isNullOrBlank()) "登录失败，点击刷新" else msg)

    // ── helpers ──────────────────────────────────────────────────────────────────────────────────

    private fun showIdentity(uin: String?, statusMsg: String, loading: Boolean) = update {
        qrView?.visibility = View.GONE
        refreshOverlay?.visibility = View.GONE
        val av = bigAvatar ?: return@update
        av.visibility = View.VISIBLE
        if (!uin.isNullOrBlank()) {
            // Only (re)load when the account actually changed. The poll re-fires these callbacks
            // repeatedly for the same uin; reloading each time cleared the photo to the gray
            // placeholder and re-downloaded, causing a periodic gray flicker over the avatar.
            if (uin != loadedAvatarUin) {
                loadedAvatarUin = uin
                av.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(M3.surfaceContainer) }
                av.setImageDrawable(null)
                av.runCatching { loadPicUrl(avatarUrl(uin)) }
            }
            nameText?.let { it.visibility = View.VISIBLE; it.text = uin }
        } else {
            loadedAvatarUin = null
            // No uin available — show a simple "scanned" check instead of the avatar.
            av.background = null
            av.setImageDrawable(MaterialSymbol(MaterialSymbols.check, M3.primary))
            nameText?.visibility = View.GONE
        }
        status(statusMsg, if (loading) M3.primary else M3.onSurfaceVariant)
        spinnerVisible(loading)
    }

    private fun showRefresh(msg: String) = update {
        bigAvatar?.visibility = View.GONE
        nameText?.visibility = View.GONE
        qrView?.visibility = View.VISIBLE
        refreshOverlay?.visibility = View.VISIBLE
        status(msg, M3.onSurfaceTip); spinnerVisible(false)
    }

    private inline fun update(crossinline block: () -> Unit) {
        if (statusText == null) return
        runOnUi { runCatching { block() } }
    }

    private fun status(text: String, color: Int) { statusText?.let { it.text = text; it.setTextColor(color) } }
    private fun spinnerVisible(v: Boolean) { spinner?.visibility = if (v) View.VISIBLE else View.GONE }

    private fun wrap() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
}
