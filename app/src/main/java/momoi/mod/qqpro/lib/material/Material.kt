package momoi.mod.qqpro.lib.material

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import momoi.mod.qqpro.lib.dp

/**
 * Reusable Material 3 (dark) building blocks for QQPro's Kotlin-DSL UI (the project uses no XML).
 *
 * Centralized here — not inlined per feature — so every redesigned screen shares one palette and one
 * set of widgets ([MaterialIconButton], [searchField], [M3Button], [M3Card], [M3ListItem], [M3Dialog]).
 * The palette mirrors [momoi.mod.qqpro.hook.MainNav] so the home nav and any new bars read as one system.
 *
 * This is the SINGLE SOURCE OF TRUTH for app colors: the legacy [momoi.mod.qqpro.Colors] object now
 * aliases these tokens, so retheming = editing the token values here. Tokens are named by MD3 role.
 * All classes are public on purpose (a @Mixin body that references a helper needs it public, or it hits
 * IllegalAccessError at runtime).
 */
object M3 {
    // ── Color tokens (all user-overridable) ──────────────────────────────────────
    // Every MD3 role below resolves LIVE from its own Settings color pref (a hex string). A blank
    // pref falls back to the built-in default (and for the primary-derived tokens, to the value
    // auto-derived from the accent). Editing a token in the 外观主题 settings category rethemes every
    // materialized screen the next time it's built. The single source of truth is here.
    // Whether to use the light baseline palette instead of the dark one (Settings.lightMode). Drives
    // each token's DEFAULT below; an explicit per-token 外观主题 override still wins when set.
    private val light get() = momoi.mod.qqpro.Settings.lightMode.value
    /** Pick the dark- or light-mode baseline default for the current [light] mode. */
    private fun dl(darkValue: Int, lightValue: Int) = if (light) lightValue else darkValue

    // ── System (Material You) color resolution ───────────────────────────────────
    // The MD3 roles a system-color palette can supply. Each token below resolves via [tok]/[sys]:
    // an explicit 外观主题 override wins; else the system value (when 跟随系统主题色 is on and the
    // device exposes Material You); else the built-in default.
    enum class SysRole {
        PRIMARY, ON_PRIMARY, PRIMARY_CONTAINER, ON_PRIMARY_CONTAINER,
        SURFACE, SURFACE_CONTAINER, SURFACE_CONTAINER_HIGH, SURFACE_VARIANT,
        ON_SURFACE, ON_SURFACE_VARIANT, ON_SURFACE_TIP, HINT, OUTLINE, OUTLINE_VARIANT,
    }

    /** System dynamic color for [role], or null when 跟随系统主题色 is off (or the device has none). */
    private fun sys(role: SysRole): Int? =
        if (momoi.mod.qqpro.Settings.followSystemTheme.value) SystemPalette.color(role) else null

    /** Token resolver: explicit hex override → system dynamic color → built-in [default]. */
    private fun tok(prefValue: String?, role: SysRole, default: Int): Int =
        parseColorOrNull(prefValue) ?: sys(role) ?: default

    /**
     * Material You (Android 12+ / API 31+) dynamic color source. Reads the system's wallpaper-derived
     * tonal palettes (`android:color/system_accent1_*`, `system_neutral1/2_*`) by resource NAME — the
     * project's compileSdk predates those symbols, but the resources exist at RUNTIME on any API 31+
     * device regardless of targetSdk (28 here). [available] is false on older devices (getIdentifier
     * returns 0), so callers fall back to the built-in palette. Public per the @Mixin helper rule.
     *
     * Android's `system_accentN_X` tones run inverted vs MD tone (X=0 → tone 100/white, X=1000 →
     * tone 0/black), so e.g. tone-80 primary = `_200`. Surface-container steps aren't exposed as
     * discrete tones, so they're derived from surface by an elevation-style blend ([surfaceTint]).
     */
    object SystemPalette {
        private val res get() = momoi.mod.qqpro.util.Utils.application.resources
        private val cache = HashMap<String, Int?>()

        /** Whether this device exposes the Material You dynamic palette at all (read once). */
        val available: Boolean by lazy {
            android.os.Build.VERSION.SDK_INT >= 31 && tone("system_accent1_500") != null
        }

        /** Read one android system color tone by name (e.g. "system_accent1_200"); null if absent. */
        private fun tone(name: String): Int? {
            if (cache.containsKey(name)) return cache[name]
            val id = res.getIdentifier(name, "color", "android")
            val c = if (id == 0) null else runCatching { res.getColor(id, null) }.getOrNull()
            cache[name] = c
            return c
        }

        /** Derive a surface-container tone from surface: blend toward white ([amount]>0) or black (<0). */
        private fun surfaceTint(amount: Float): Int? {
            val base = color(SysRole.SURFACE) ?: return null
            return if (amount >= 0) blend(base, 0xFF_FFFFFF.toInt(), amount)
            else blend(base, 0xFF_000000.toInt(), -amount)
        }

        /** Map an MD3 [role] onto the system tonal palette for the current light/dark mode. */
        fun color(role: SysRole): Int? {
            if (!available) return null
            val dark = !light
            return when (role) {
                SysRole.PRIMARY                -> tone(if (dark) "system_accent1_200" else "system_accent1_600")
                SysRole.ON_PRIMARY             -> tone(if (dark) "system_accent1_800" else "system_accent1_0")
                SysRole.PRIMARY_CONTAINER      -> tone(if (dark) "system_accent1_700" else "system_accent1_100")
                SysRole.ON_PRIMARY_CONTAINER   -> tone(if (dark) "system_accent1_100" else "system_accent1_900")
                SysRole.SURFACE                -> tone(if (dark) "system_neutral1_900" else "system_neutral1_10")
                SysRole.SURFACE_CONTAINER      -> surfaceTint(if (dark) 0.05f else -0.03f)
                SysRole.SURFACE_CONTAINER_HIGH -> surfaceTint(if (dark) 0.09f else -0.06f)
                SysRole.SURFACE_VARIANT        -> tone(if (dark) "system_neutral2_700" else "system_neutral2_100")
                SysRole.ON_SURFACE             -> tone(if (dark) "system_neutral1_100" else "system_neutral1_900")
                SysRole.ON_SURFACE_VARIANT     -> tone(if (dark) "system_neutral2_200" else "system_neutral2_700")
                SysRole.ON_SURFACE_TIP         -> tone(if (dark) "system_neutral2_300" else "system_neutral2_600")
                SysRole.HINT                   -> tone(if (dark) "system_neutral2_400" else "system_neutral2_500")
                SysRole.OUTLINE                -> tone(if (dark) "system_neutral2_400" else "system_neutral2_500")
                SysRole.OUTLINE_VARIANT        -> tone(if (dark) "system_neutral2_700" else "system_neutral2_200")
            }
        }
    }

    // Accent (primary). Dark = Material Blue 200 (softer tone for dark UIs); Light = Blue 700, a deeper
    // MD3-tone-40-style accent with enough contrast on light surfaces. White label on the accent.
    val DEFAULT_PRIMARY get() = dl(0xFF_90CAF9.toInt(), 0xFF_1976D2.toInt())

    val primary: Int get() = tok(momoi.mod.qqpro.Settings.themeColor.value, SysRole.PRIMARY, DEFAULT_PRIMARY)
    /**
     * Label/icon color on a filled primary surface. Blank pref = auto: white on a normal accent (the
     * right look for a dark UI); only a very LIGHT accent (e.g. pale yellow) flips to a dark tone for
     * contrast. Avoids the near-black text a blanket darken() produced on mid-bright accents.
     */
    val onPrimary: Int get() = parseColorOrNull(momoi.mod.qqpro.Settings.themeOnPrimary.value)
        ?: sys(SysRole.ON_PRIMARY)
        ?: if (luminance(primary) > 0.72f) darken(primary, 0.78f) else 0xFF_FFFFFF.toInt()
    /** Translucent tonal container; blank pref = the system tonal (opaque) or auto (accent at 0x33). */
    val primaryContainer: Int get() = parseColorOrNull(momoi.mod.qqpro.Settings.themePrimaryContainer.value)
        ?: sys(SysRole.PRIMARY_CONTAINER)
        ?: ((primary and 0x00FFFFFF) or 0x33_000000)
    val onPrimaryContainer: Int get() = parseColorOrNull(momoi.mod.qqpro.Settings.themeOnPrimaryContainer.value)
        ?: sys(SysRole.ON_PRIMARY_CONTAINER)
        ?: primary
    /**
     * OPAQUE version of [primaryContainer] (which is translucent). Use where the fill is rasterized
     * with no live background behind it — e.g. an avatar/icon drawable — so the translucent tonal
     * doesn't composite over black and turn dark in light mode. Composited over [surface].
     */
    val tonalSolid: Int get() = compositeOver(primaryContainer, surface)

    // ── Surfaces (light defaults = MD3 baseline neutral surfaces / surface-container tones) ───────
    val surface: Int get() = tok(momoi.mod.qqpro.Settings.themeSurface.value, SysRole.SURFACE, dl(0xFF_1A1A1A.toInt(), 0xFF_FFFBFE.toInt()))              // screen background
    val surfaceContainer: Int get() = tok(momoi.mod.qqpro.Settings.themeSurfaceContainer.value, SysRole.SURFACE_CONTAINER, dl(0xFF_222222.toInt(), 0xFF_F3EDF7.toInt()))     // field / sunken surface / card
    // MD3 对话气泡（对方）：介于 surface 与 surfaceContainer 之间的低一级容器色。
    val surfaceContainerLow: Int get() = tok(momoi.mod.qqpro.Settings.themeSurfaceContainer.value, SysRole.SURFACE_CONTAINER, dl(0xFF_1E1E1E.toInt(), 0xFF_F7F2FA.toInt()))
    val surfaceContainerHigh: Int get() = tok(momoi.mod.qqpro.Settings.themeSurfaceContainerHigh.value, SysRole.SURFACE_CONTAINER_HIGH, dl(0xFF_2A2A2A.toInt(), 0xFF_ECE6F0.toInt())) // raised card / row pressed
    val surfaceVariant: Int get() = tok(momoi.mod.qqpro.Settings.themeSurfaceVariant.value, SysRole.SURFACE_VARIANT, dl(0xFF_2E2E2E.toInt(), 0xFF_E7E0EC.toInt()))

    // ── Content (light defaults = MD3 baseline on-surface / outline tones) ───────────────────────
    val onSurface: Int get() = tok(momoi.mod.qqpro.Settings.themeOnSurface.value, SysRole.ON_SURFACE, dl(0xFF_FFFFFF.toInt(), 0xFF_1C1B1F.toInt()))
    val onSurfaceVariant: Int get() = tok(momoi.mod.qqpro.Settings.themeOnSurfaceVariant.value, SysRole.ON_SURFACE_VARIANT, dl(0xFF_C9C7CE.toInt(), 0xFF_49454F.toInt()))     // inactive icon/text
    val onSurfaceTip: Int get() = tok(momoi.mod.qqpro.Settings.themeOnSurfaceTip.value, SysRole.ON_SURFACE_TIP, dl(0xFF_999999.toInt(), 0xFF_6A6A70.toInt()))         // secondary/hint text
    val hint: Int get() = tok(momoi.mod.qqpro.Settings.themeHint.value, SysRole.HINT, dl(0xFF_777777.toInt(), 0xFF_8A8A90.toInt()))
    val outline: Int get() = tok(momoi.mod.qqpro.Settings.themeOutline.value, SysRole.OUTLINE, dl(0xFF_444444.toInt(), 0xFF_79747E.toInt()))
    val outlineVariant: Int get() = tok(momoi.mod.qqpro.Settings.themeOutlineVariant.value, SysRole.OUTLINE_VARIANT, dl(0xFF_333333.toInt(), 0xFF_CAC4D0.toInt()))

    // ── Status ─────────────────────────────────────────────────────────────────
    val error: Int get() = parseColor(momoi.mod.qqpro.Settings.themeError.value, dl(0xFF_E5443C.toInt(), 0xFF_B3261E.toInt()))
    val badge: Int get() = error                   // unread/notification badge follows the error token
    val legacyBtn = 0xFF_1B9AF7.toInt()            // older link/button blue (Colors.btn)

    // ── Chat tokens (referenced by Colors aliases; values must stay exact) ───────
    val replyText = 0xFF_DDDDDD.toInt()
    val replyBackground = 0x22_FFFFFF
    val atMe = 0xFF_F74C30.toInt()

    // Group role/level tag badges (头衔/普通/管理/群主). Mode-aware: the dark values are unchanged; light
    // mode uses a pale tint background with a deeper, readable text color so the tags don't show as dark
    // chips on a light bubble. (Nested object can use M3's private [dl].)
    object NickTag {
        val specialBg get() = dl(0xFF_30263F.toInt(), 0xFF_ECE3FB.toInt()); val specialText get() = dl(0xFF_BB87F7.toInt(), 0xFF_6A3FD0.toInt())
        val normalBg  get() = dl(0xFF_2E2E2E.toInt(), 0xFF_E4E4E4.toInt()); val normalText  get() = dl(0xFF_9E9E9E.toInt(), 0xFF_5F5F5F.toInt())
        val adminBg   get() = dl(0xFF_0E2D41.toInt(), 0xFF_D6E9FB.toInt()); val adminText   get() = dl(0xFF_0088EE.toInt(), 0xFF_0066CC.toInt())
        val ownerBg   get() = dl(0xFF_412917.toInt(), 0xFF_FCE6D2.toInt()); val ownerText   get() = dl(0xFF_FF8D40.toInt(), 0xFF_C25700.toInt())
    }

    // ── Shape / dimens ───────────────────────────────────────────────────────────
    val radiusSm get() = 8.dp.toFloat()
    val radiusMd get() = 12.dp.toFloat()
    val radiusLg get() = 16.dp.toFloat()
    val radiusPill = 9999f

    // ── Deprecated aliases (kept so existing call sites keep compiling) ──────────
    val ACCENT get() = primary
    val TONAL get() = primaryContainer
    val ON_SURFACE_VARIANT get() = onSurfaceVariant
    val BADGE get() = badge
    val SURFACE get() = surfaceContainer
    val HINT get() = hint
    val ON_SURFACE get() = onSurface

    /**
     * Parse a hex color string (`#RRGGBB`, `RRGGBB`, `#AARRGGBB` or `AARRGGBB`, with optional spaces);
     * a parse without an alpha component is treated as fully opaque. Returns [fallback] when blank/invalid.
     */
    fun parseColor(raw: String?, fallback: Int): Int = parseColorOrNull(raw) ?: fallback

    /** Like [parseColor] but returns null (not a fallback) when the string is blank/invalid. */
    fun parseColorOrNull(raw: String?): Int? {
        val s = raw?.trim()?.removePrefix("#")?.replace(" ", "") ?: return null
        if (s.length != 6 && s.length != 8) return null
        val v = s.toLongOrNull(16) ?: return null
        return if (s.length == 6) (0xFF000000.toInt() or v.toInt()) else v.toInt()
    }

    /**
     * Best-contrast label color (near-black or white) for content drawn ON a filled [bg] of an
     * arbitrary color. Use for any button/badge whose background isn't a fixed token — e.g. the
     * accent (which is now a light tone by default), error red, or a status color — so the label
     * stays legible regardless of theme. (For a background that is exactly [primary], [onPrimary] is
     * the equivalent purpose-built token.)
     */
    fun onColor(bg: Int): Int = if (luminance(bg) > 0.6f) 0xFF_111111.toInt() else 0xFF_FFFFFF.toInt()

    /** Perceived luminance of [color] in 0..1 (ignores alpha). */
    fun luminance(color: Int): Float {
        val r = (color shr 16 and 0xFF) / 255f
        val g = (color shr 8 and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        return 0.299f * r + 0.587f * g + 0.114f * b
    }

    /** Blend a color toward black by [amount] (0 = unchanged, 1 = black); alpha preserved. */
    /** Composite a (possibly translucent) [fg] over an opaque [bg], returning an opaque color. */
    fun compositeOver(fg: Int, bg: Int): Int {
        val a = (fg ushr 24 and 0xFF) / 255f
        fun mix(sh: Int) = (((fg shr sh and 0xFF) * a) + ((bg shr sh and 0xFF) * (1f - a))).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    /** Per-channel linear blend from [a] toward [b] by [t] in 0..1; result is opaque. */
    fun blend(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun mix(sh: Int) = ((a shr sh and 0xFF) * (1 - k) + (b shr sh and 0xFF) * k).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    fun darken(color: Int, amount: Float): Int {
        val k = (1f - amount).coerceIn(0f, 1f)
        val a = color ushr 24 and 0xFF
        val r = ((color shr 16 and 0xFF) * k).toInt()
        val g = ((color shr 8 and 0xFF) * k).toInt()
        val b = ((color and 0xFF) * k).toInt()
        return (a shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** A solid rounded rect drawable. */
    fun rounded(color: Int, radius: Float = radiusMd): GradientDrawable = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    /** A stroked (outlined) rounded rect drawable. */
    fun outlined(strokeColor: Int, radius: Float = radiusMd, strokeWidthDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(0)
            cornerRadius = radius
            setStroke(strokeWidthDp.dp, strokeColor)
        }

    /** A rounded rect that is BOTH filled ([fillColor]) and outlined ([strokeColor]) — a filled field
     *  with a visible border (e.g. the materialized chat input). */
    fun filledOutlined(
        fillColor: Int, strokeColor: Int, radius: Float = radiusMd, strokeWidthDp: Int = 1,
    ): GradientDrawable = GradientDrawable().apply {
        setColor(fillColor)
        cornerRadius = radius
        setStroke(strokeWidthDp.dp, strokeColor)
    }

    /**
     * Wrap a content drawable with an M3 ripple (pressed feedback). The mask BOUNDS the ripple: when
     * no [content] is given we still pass an opaque mask so the ripple is clipped to the view's bounds
     * instead of spilling out as an unbounded full circle.
     */
    fun ripple(content: Drawable?, rippleColor: Int = 0x33_FFFFFF): Drawable {
        val mask = content ?: ColorDrawable(Color.WHITE)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask)
        } else {
            // This watch is Android 4.4 (API 19): RippleDrawable doesn't exist there. Fall back to a
            // StateListDrawable that shows the ripple color while pressed, over the same mask.
            StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(rippleColor))
                // 默认态（未按压）必须透明：content==null 时 mask 是纯白 ColorDrawable，
                // 直接画 mask 会让 M3ListItem 等整行纯白（深色模式下「我的」页操作横幅、
                // 群聊设置横幅全白就是这个）。API 21+ 的 RippleDrawable 无 content 时
                // 默认不画任何东西，这里保持一致。
                addState(IntArray(0), content ?: ColorDrawable(Color.TRANSPARENT))
            }
        }
    }

    /** A rounded, sunken M3 search/text field. */
    fun searchField(ctx: Context, hint: String): EditText = EditText(ctx).apply {
        this.hint = hint
        setHintTextColor(M3.hint)
        setTextColor(onSurface)
        textSize = 13f
        setSingleLine()
        setPadding(12.dp, 6.dp, 12.dp, 6.dp)
        background = rounded(surfaceContainer, radiusMd)
    }
}

/**
 * A circular tonal Material icon button with an optional top-end unread badge. Self-contained
 * (no XML, no Material lib dependency — the watch theme isn't MaterialComponents-based, so a real
 * com.google.android.material button would crash). Reuse for any toolbar/action-bar action.
 */
class MaterialIconButton(ctx: Context) : FrameLayout(ctx) {
    private val icon = ImageView(ctx).apply {
        scaleType = ImageView.ScaleType.FIT_CENTER
    }
    private val badge = TextView(ctx).apply {
        setTextColor(0xFF_FFFFFF.toInt())
        textSize = 8f
        gravity = Gravity.CENTER
        typeface = Typeface.DEFAULT_BOLD
        background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE; cornerRadius = 9999f; setColor(M3.BADGE)
        }
        setPadding(3.dp, 0, 3.dp, 0)
        minWidth = 12.dp
        visibility = View.GONE
    }

    init {
        clipChildren = false
        clipToPadding = false
        isClickable = true
        // No container/tint by default: many app icons are already full-color round badges, so a
        // tonal circle behind them and an accent tint just collapse them into blobs. Opt in via
        // [setTonalContainer] / [setIconTint] for monochrome icons that need it.
        addView(icon, LayoutParams(22.dp, 22.dp, Gravity.CENTER))
        addView(badge, LayoutParams(LayoutParams.WRAP_CONTENT, 13.dp, Gravity.TOP or Gravity.END))
    }

    /** Set the icon from a resource name (app resources aren't on the compile-time R). */
    fun setIconResName(name: String) {
        val id = resources.getIdentifier(name, "drawable", context.packageName)
        if (id != 0) runCatching { icon.setImageResource(id) }
    }

    /** Set the icon from a drawable (e.g. a self-drawn [MaterialIcon]). */
    fun setIcon(drawable: android.graphics.drawable.Drawable) = icon.setImageDrawable(drawable)

    fun setIconTint(color: Int) = icon.setColorFilter(color)

    /** Enable the M3 tonal circular container behind the icon (for monochrome/line icons). */
    fun setTonalContainer() {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(M3.TONAL) }
    }

    /** Show/hide the unread badge ("99+" cap); count <= 0 hides it. */
    fun setBadgeCount(count: Int) {
        if (count > 0) {
            badge.text = if (count > 99) "99+" else count.toString()
            badge.visibility = View.VISIBLE
        } else {
            badge.visibility = View.GONE
        }
    }
}
