package momoi.mod.qqpro.lib
import momoi.mod.qqpro.lib.setClipToOutlineCompat

import android.graphics.drawable.Drawable
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import androidx.core.view.marginBottom
import androidx.core.view.marginLeft
import androidx.core.view.marginRight
import androidx.core.view.marginTop

const val WRAP = WRAP_CONTENT
const val FILL = MATCH_PARENT
fun <T : View> T.layoutParams(
    params: LayoutParams
) = apply {
    layoutParams = params
}
fun <T : View> T.margin(
    left: Int = marginLeft,
    top: Int = marginTop,
    right: Int = marginRight,
    bottom: Int = marginBottom
): T =
    apply {
        val params = layoutParams as? ViewGroup.MarginLayoutParams
        params?.setMargins(left, top, right, bottom)
    }

fun <T : View> T.marginHorizontal(value: Int) = margin(left = value, right = value)
fun <T : View> T.marginVertical(value: Int) = margin(top = value, bottom = value)

fun <T : View> T.padding(left: Int = paddingLeft, top: Int = paddingTop, right: Int = paddingRight, bottom: Int = paddingBottom) = apply {
    setPadding(left, top, right, bottom)
}
fun <T : View> T.paddingHorizontal(value: Int) = padding(left = value, right = value)
fun <T : View> T.paddingVertical(value: Int) = padding(top = value, bottom = value)
fun <T : View> T.padding(value: Int) = padding(value, value, value, value)

fun <T : View> T.background(color: Int) = apply {
    setBackgroundColor(color)
}
fun <T : View> T.background(color: Long) = apply {
    setBackgroundColor(color.toInt())
}
fun <T : View> T.background(drawable: Drawable?) = apply {
    background = drawable
}
fun <T : View> T.size(width: Int = layoutParams.width, height: Int = layoutParams.height) = apply {
    layoutParams.width = width
    layoutParams.height = height
}
fun <T : View> T.size(value: Int) = size(value, value)
fun <T : View> T.width(value: Int) = size(width = value)
fun <T : View> T.height(value: Int) = size(height = value)
fun <T : View> T.id(value: Int) = apply {
    id = value
}

/**
 * Add an M3 press ripple as a FOREGROUND overlay, so it layers over the view's existing background
 * (rounded card fill, pill color, …) without replacing it — giving tappable rows a touch response.
 * When [clip] (default) the ripple is clipped to the view's outline so it follows rounded corners;
 * pass clip=false for views with no rounded background (a plain row), where outline-clipping would
 * clip the content away. Foreground on arbitrary views needs API 23 — a no-op below that.
 */
fun <T : View> T.rippleTouch(clip: Boolean = true) = apply {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        foreground = momoi.mod.qqpro.lib.material.M3.ripple(null)
        if (clip) setClipToOutlineCompat(true)
    }
}

/** clipToOutline is API 21+ and this watch ROM is API 19 — the setter is missing there. */
fun View.setClipToOutlineCompat(clip: Boolean) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) clipToOutline = clip
}

/** elevation (View.setElevation) is API 21+ — missing on the API 19 watch. */
fun View.setElevationCompat(elevationValue: Float) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) elevation = elevationValue
}

inline fun <T : View> T.clickable(crossinline onClick: ()->Unit) = apply {
    setOnClickListener {
        onClick()
    }
}

inline fun <T : View> T.longClickable(crossinline onLongClick: ()->Unit) = apply {
    setOnLongClickListener {
        onLongClick()
        true
    }
}

// Non-inline on purpose: keeps the SAM impl in this package, not inlined into a
// @Mixin method body (which lives in a different package and can't access it).
fun <T : View> T.onFocusChange(onChange: (Boolean)->Unit) = apply {
    setOnFocusChangeListener { _, hasFocus -> onChange(hasFocus) }
}

// Non-inline on purpose (see [onFocusChange]): the OnClickListener SAM impl must
// live in this package so a @Mixin method body in another package can reach it.
fun <T : View> T.onClick(onClick: ()->Unit) = apply {
    setOnClickListener { onClick() }
}
