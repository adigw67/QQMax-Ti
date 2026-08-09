package momoi.mod.qqpro.lib.material

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.InputFilter
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.widget.doAfterTextChanged
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Reusable Material color-picker for the watch settings UI (no XML, no AndroidX color picker — the
 * bundled appcompat is far too old). Three synced ways to pick a color:
 *  - a [ColorWheelView] HSV wheel + brightness slider (easy free-hand input),
 *  - a grid of preset swatches drawn from the official Material dark-theme guidelines ([MaterialColors]),
 *  - a hex text field (kept, per request).
 *
 * Public on purpose (referenced from the @Mixin settings activity body — package-private would throw
 * IllegalAccessError at runtime). Drives [showColorPicker], used for every M3 token and the chat
 * bubble / text / link color settings.
 */

/**
 * Preset color palettes sourced from the official Material Design dark-theme guidelines (NOT hand
 * invented):
 *  - [ACCENT]: the Material 2 accent palette **200 tones**, which the dark-theme guidance recommends
 *    as accent colors on dark surfaces ("use lighter, less-saturated 200–50 tones"), plus the M3
 *    baseline dark primary/tertiary tones and the app's default accent.
 *  - [SURFACE]: M3 dark neutral surface tones (incl. the #121212 baseline dark surface and the M3
 *    baseline #1C1B1F / surfaceVariant #49454F) and the app's own surface ramp.
 *  - [ON]: M3 dark "on"/neutral content tones (onSurface #E6E1E5, onSurfaceVariant #CAC4D0,
 *    outline #938F99) plus white and the app's grey text tones.
 *  - [ERROR]: the M3 dark error roles (error #F2B8B5, errorContainer #8C1D18, onError #601410) and
 *    the M2 dark error #CF6679.
 *
 * Sources: m3.material.io (baseline dark color scheme) and material.io dark-theme accent guidance.
 */
object MaterialColors {
    // Material 2 accent palette — 200 tones (recommended for dark themes) — + M3 baseline accents.
    val ACCENT = listOf(
        "#EF9A9A", "#F48FB1", "#CE93D8", "#B39DDB", "#9FA8DA", "#90CAF9",
        "#81D4FA", "#4FC3F7", "#80DEEA", "#80CBC4", "#A5D6A7", "#C5E1A5",
        "#E6EE9C", "#FFF59D", "#FFE082", "#FFCC80", "#FFAB91", "#BCAAA4",
        "#D0BCFF", "#EFB8C8", "#6750A4",
    )

    // M3 dark neutral surfaces / containers + the app's surface ramp.
    val SURFACE = listOf(
        "#000000", "#121212", "#1A1A1A", "#1C1B1F", "#1E1E1E", "#222222",
        "#242424", "#2A2A2A", "#2E2E2E", "#333333", "#49454F", "#4A4458",
    )

    // M3 dark "on"/content tones + white + the app's grey text tones.
    val ON = listOf(
        "#FFFFFF", "#E6E1E5", "#E8DEF8", "#CAC4D0", "#C9C7CE", "#B0BEC5",
        "#999999", "#938F99", "#777777", "#444444",
    )

    // M3 dark error roles + M2 dark error.
    val ERROR = listOf(
        "#F2B8B5", "#E5443C", "#CF6679", "#EF9A9A", "#B00020", "#8C1D18",
        "#FFB4AB", "#601410",
    )
}

/**
 * An HSV color wheel: angle = hue, distance from center = saturation. Brightness (value) is driven
 * externally by [setBrightnessFromUser] (a slider in the dialog). Touch picks hue+saturation.
 *
 * [setColor] updates the wheel WITHOUT firing [onColorChanged] (so the dialog can push the hex-field
 * value in without a feedback loop); user gestures (touch / brightness) DO fire it.
 */
@SuppressLint("ViewConstructor")
class ColorWheelView(activity: Activity) : View(activity) {
    /** Fired only on user gestures (wheel touch, brightness change), never on programmatic [setColor]. */
    var onColorChanged: ((Int) -> Unit)? = null

    // hue 0..360, saturation 0..1, value 0..1
    private val hsv = floatArrayOf(0f, 1f, 1f)

    private val huePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val satPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dimPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val selStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f.dpPx()
        color = Color.WHITE
    }

    private var cx = 0f
    private var cy = 0f
    private var radius = 0f

    private fun Float.dpPx() = this * resources.displayMetrics.density

    val color: Int get() = Color.HSVToColor(hsv)
    val brightness: Float get() = hsv[2]

    /** Set the wheel from a color (updates hue/sat/value). Does NOT fire [onColorChanged]. */
    fun setColor(c: Int) {
        Color.colorToHSV(c, hsv)
        invalidate()
    }

    /** Change only the value/brightness from the slider; fires [onColorChanged]. */
    fun setBrightnessFromUser(v: Float) {
        hsv[2] = v.coerceIn(0f, 1f)
        invalidate()
        onColorChanged?.invoke(color)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        cx = w / 2f
        cy = h / 2f
        radius = min(w, h) / 2f - 8f.dpPx()
        // Hue around the circle, matching Color HSV order (0=red, 60=yellow, 120=green, ...).
        huePaint.shader = SweepGradient(
            cx, cy,
            intArrayOf(
                Color.RED, Color.YELLOW, Color.GREEN, Color.CYAN,
                Color.BLUE, Color.MAGENTA, Color.RED
            ),
            null
        )
        // White center -> transparent edge gives the saturation falloff toward the middle.
        satPaint.shader = RadialGradient(
            cx, cy, radius,
            Color.WHITE, 0x00FFFFFF, Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (radius <= 0f) return
        canvas.drawCircle(cx, cy, radius, huePaint)
        canvas.drawCircle(cx, cy, radius, satPaint)
        // Dim the whole wheel by (1 - value) so it visually reflects the brightness slider.
        dimPaint.color = Color.argb(((1f - hsv[2]) * 255).toInt(), 0, 0, 0)
        canvas.drawCircle(cx, cy, radius, dimPaint)
        // Selector ring at the current hue/saturation.
        val ang = Math.toRadians(hsv[0].toDouble())
        val d = hsv[1] * radius
        val sx = cx + (cos(ang) * d).toFloat()
        val sy = cy + (sin(ang) * d).toFloat()
        selFill.color = color
        canvas.drawCircle(sx, sy, 8f.dpPx(), selFill)
        canvas.drawCircle(sx, sy, 8f.dpPx(), selStroke)
    }

    // Tap-only: we deliberately do NOT consume drags or call requestDisallowInterceptTouchEvent,
    // so a vertical drag that starts on the wheel scrolls the enclosing dialog ScrollView normally
    // (the ScrollView intercepts the move and the wheel just gets a CANCEL). A pick happens on UP
    // only when the finger barely moved.
    private var downX = 0f
    private var downY = 0f
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y
                return true // claim the gesture so we still receive UP for a tap
            }
            MotionEvent.ACTION_UP -> {
                if (abs(event.x - downX) <= touchSlop && abs(event.y - downY) <= touchSlop) {
                    pickAt(event.x, event.y)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    /** Set hue/saturation from a tapped point and notify. */
    private fun pickAt(x: Float, y: Float) {
        val dx = x - cx
        val dy = y - cy
        var deg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        if (deg < 0) deg += 360f
        hsv[0] = deg
        hsv[1] = (hypot(dx, dy) / radius).coerceIn(0f, 1f)
        invalidate()
        onColorChanged?.invoke(color)
    }
}

/** Format an opaque color as `#RRGGBB`. */
fun colorToHex(c: Int): String = String.format("#%06X", c and 0xFFFFFF)

/**
 * Show the Material color picker. [initial] is the current hex (may be blank); [fallback] is the
 * effective color to seed the wheel with when [initial] is blank. [presets] are the swatches to
 * offer. [onPick] receives the chosen hex, or "" when the user taps 默认 (only shown if [allowDefault]).
 */
@SuppressLint("SetTextI18n")
fun showColorPicker(
    activity: Activity,
    title: String,
    initial: String,
    fallback: Int,
    allowDefault: Boolean,
    presets: List<String>,
    onPick: (String) -> Unit,
) {
    val dialog = Dialog(activity)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    fun dp(v: Int) = (v * activity.resources.displayMetrics.density).toInt()
    val screenW = activity.resources.displayMetrics.widthPixels

    val panel = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(12))
        background = GradientDrawable().apply {
            setColor(M3.surfaceContainerHigh)
            cornerRadius = dp(22).toFloat()
        }
    }

    panel.addView(TextView(activity).apply {
        text = title
        textSize = 15f
        setTextColor(M3.onSurface)
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, dp(12))
    }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

    val initialColor = M3.parseColorOrNull(initial) ?: fallback

    val wheel = ColorWheelView(activity).apply { setColor(initialColor) }
    val wheelSize = (screenW * 0.6f).toInt()
    panel.addView(wheel, LinearLayout.LayoutParams(wheelSize, wheelSize).apply { gravity = Gravity.CENTER_HORIZONTAL })

    // Brightness slider — the self-drawn M3 slider (a plain SeekBar renders wrong at watch DPI).
    val bright = M3Slider(activity).apply {
        max = 100
        progress = (wheel.brightness * 100).toInt()
        onProgressChanged = { p, fromUser -> if (fromUser) wheel.setBrightnessFromUser(p / 100f) }
    }
    panel.addView(TextView(activity).apply {
        text = "亮度"
        textSize = 11f
        setTextColor(M3.onSurfaceVariant)
        setPadding(0, dp(8), 0, dp(2))
    })
    panel.addView(bright, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

    // Preview chip + hex field.
    val preview = View(activity)
    val hexField = EditText(activity).apply {
        setText(colorToHex(initialColor))
        textSize = 14f
        setTextColor(M3.onSurface)
        setSingleLine()
        filters = arrayOf(InputFilter.LengthFilter(7))
        background = GradientDrawable().apply {
            setColor(M3.surfaceContainer)
            cornerRadius = dp(10).toFloat()
        }
        setPadding(dp(12), dp(8), dp(12), dp(8))
    }
    val previewRow = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(10), 0, dp(4))
    }
    previewRow.addView(preview, LinearLayout.LayoutParams(dp(28), dp(28)).apply { rightMargin = dp(10) })
    previewRow.addView(hexField, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
    panel.addView(previewRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

    fun updatePreview(c: Int) {
        preview.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(c)
            setStroke(dp(1), M3.outline)
        }
    }
    updatePreview(initialColor)

    // ── Sync wiring (suppress flags break the wheel <-> field feedback loop) ───────────
    var suppressText = false
    wheel.onColorChanged = { c ->
        suppressText = true
        hexField.setText(colorToHex(c))
        suppressText = false
        bright.progress = (wheel.brightness * 100).toInt() // programmatic (fromUser=false), ignored below
        updatePreview(c)
    }
    hexField.doAfterTextChanged {
        if (suppressText) return@doAfterTextChanged
        val c = M3.parseColorOrNull(it?.toString())
        if (c != null) {
            wheel.setColor(c)
            bright.progress = (wheel.brightness * 100).toInt()
            updatePreview(c)
        }
    }
    // Preset swatches in an auto-wrapping flow — fills the available width and re-flows to the
    // next line so nothing clips off a narrow round screen. Tap routes through the hex field so
    // everything stays in sync.
    val flow = FlowLayout(activity, dp(8), dp(8)).apply { setPadding(0, dp(6), 0, 0) }
    for (hex in presets) {
        val sw = View(activity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(M3.parseColor(hex, M3.surfaceContainer))
                setStroke(dp(1), M3.outlineVariant)
            }
            setOnClickListener { hexField.setText(hex) }
        }
        flow.addView(sw, ViewGroup.LayoutParams(dp(30), dp(30)))
    }
    panel.addView(flow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

    // Action buttons — stacked vertically like the other M3 dialogs (full-width M3Button stack).
    val actions = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(14), 0, 0)
    }
    fun stackButton(label: String, variant: M3Button.Variant, onTap: () -> Unit) {
        val b = M3Button(activity).variant(variant).apply {
            text = label
            setOnClickListener { onTap() }
        }
        actions.addView(b, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(8)
        })
    }
    stackButton("确定", M3Button.Variant.FILLED) {
        val chosen = M3.parseColorOrNull(hexField.text?.toString())?.let { colorToHex(it) }
            ?: colorToHex(wheel.color)
        onPick(chosen)
        dialog.dismiss()
    }
    if (allowDefault) stackButton("默认", M3Button.Variant.TONAL) { onPick(""); dialog.dismiss() }
    stackButton("取消", M3Button.Variant.TEXT) { dialog.dismiss() }
    panel.addView(actions, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

    // Scroll so the panel fits a short round screen; rounded bg stays on the panel.
    val scroll = ScrollView(activity).apply {
        isFillViewport = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(panel, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    dialog.setContentView(scroll)
    dialog.window?.apply {
        setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val w = (screenW * 0.86f).toInt()
        val maxH = (activity.resources.displayMetrics.heightPixels * 0.9f).toInt()
        panel.measure(
            View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val h = if (panel.measuredHeight > maxH) maxH else ViewGroup.LayoutParams.WRAP_CONTENT
        setLayout(w, h)
    }
    dialog.show()
}

/**
 * Minimal flow container: lays children left-to-right, wrapping to the next line when the row is
 * full. Used for the preset swatch grid so it adapts to any (narrow, round) screen width instead of
 * a fixed column count that clips. Children keep their own measured size; [hGap]/[vGap] space them.
 */
private class FlowLayout(context: Context, private val hGap: Int, private val vGap: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val avail = width - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            measureChild(c, widthMeasureSpec, heightMeasureSpec)
            if (x > 0 && x + c.measuredWidth > avail) { x = 0; y += rowH + vGap; rowH = 0 }
            x += c.measuredWidth + hGap
            rowH = maxOf(rowH, c.measuredHeight)
        }
        val totalH = paddingTop + paddingBottom + y + rowH
        setMeasuredDimension(width, resolveSize(totalH, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val avail = r - l - paddingLeft - paddingRight
        var x = paddingLeft
        var y = paddingTop
        var rowH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > paddingLeft && x - paddingLeft + c.measuredWidth > avail) {
                x = paddingLeft; y += rowH + vGap; rowH = 0
            }
            c.layout(x, y, x + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + hGap
            rowH = maxOf(rowH, c.measuredHeight)
        }
    }
}
