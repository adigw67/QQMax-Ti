package momoi.mod.qqpro.drawable

import android.content.Context
import android.graphics.drawable.GradientDrawable
import momoi.mod.qqpro.lib.GroupScope

fun roundCornerDrawable(
    color: Int,
    radius: Float
) = roundCornerDrawable(color, radius, radius, radius, radius)

fun roundCornerDrawable(
    color: Int,
    topLeftRadius: Float,
    topRightRadius: Float,
    bottomLeftRadius: Float,
    bottomRightRadius: Float
): GradientDrawable {
    val drawable = GradientDrawable()
    drawable.shape = GradientDrawable.RECTANGLE
    drawable.setColor(color)
    drawable.cornerRadii = floatArrayOf(
        topLeftRadius, topLeftRadius,
        topRightRadius, topRightRadius,
        bottomRightRadius, bottomRightRadius,
        bottomLeftRadius, bottomLeftRadius
    )
    return drawable
}