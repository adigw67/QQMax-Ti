package momoi.mod.qqpro.lib

import android.annotation.SuppressLint
import android.content.res.Resources
import android.util.TypedValue
import momoi.mod.qqpro.util.Utils

val Int.dp @SuppressLint("WrongConstant")
get() = dpf.toInt()

val Int.dpf get() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, toFloat(), Utils.application.resources.displayMetrics)
val Float.dpf get() = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this, Utils.application.resources.displayMetrics)
val Float.vh get() = (this * Utils.heightPixels).toInt()