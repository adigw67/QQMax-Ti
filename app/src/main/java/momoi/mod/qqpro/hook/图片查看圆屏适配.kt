package momoi.mod.qqpro.hook

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import com.tencent.biz.richframework.delegate.impl.RFWLog
import com.tencent.richframework.widget.matrix.RFWMatrixImageView
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.util.Utils
import kotlin.math.sqrt

private val size = ((Utils.heightPixels - Utils.heightPixels / sqrt(2f)) / 2).toInt()

@Mixin
class 图片查看圆屏适配(context: Context?, attributeSet: AttributeSet?) : RFWMatrixImageView(
    context,
    attributeSet
) {
    private val actualHeight: Int = 0
    private val actualWidth: Int = 0

    override fun setMaximumScale(f: Float) {
        super.setMaximumScale(f * 3f)
    }

    override fun c(p0: Boolean): Boolean {
        if ((this as ImageView).drawable == null) {
            RFWLog.b("RFWMatrixImageView", RFWLog.e, *arrayOf<Any>("drawable is null"))
            return false
        } else {
            var var3 = (this as ImageView).height.toFloat()
            var var2 = (this as ImageView).width.toFloat()
            if (!(var3 <= 0.0f) && !(var2 <= 0.0f)) {
                var var4 = this.actualHeight
                val var6 = this.actualWidth
                if (var4 > 0 && var6 > 0) {
                    val var5 = RFWLog.e
                    var var7 = StringBuilder()
                    var7.append("display height is ")
                    var7.append(var3)
                    var7.append("actualHeight is ")
                    var7.append(var4)
                    var7.append("display width is ")
                    var7.append(var2)
                    var7.append("actualWidth is ")
                    var7.append(var6)
                    RFWLog.e("RFWMatrixImageView", var5, var7.toString())
                    var3 = var4.toFloat() / var3 / (var6.toFloat() / var2)
                    if (var3 <= 3.0f) {
                        RFWLog.e("RFWMatrixImageView", RFWLog.e, "not long pic, no need to scale")
                        return true
                    } else {
                        var2 = var3
                        super.setMaximumScale(var3 * 1.1f)
                        var4 = RFWLog.e
                        var7 = StringBuilder()
                        var7.append("target zoom is ")
                        var7.append(var2)
                        RFWLog.e("RFWMatrixImageView", var4, var7.toString())
                        var4 = (this as ImageView).left
                        var3 = ((this as ImageView).right + var4).toFloat() / 2.0f
                        c.l(var2, var3, 0.0f, p0)
                        this.setIsAutoMagnified(true)
                        return true
                    }
                } else {
                    RFWLog.b(
                        "RFWMatrixImageView",
                        RFWLog.e,
                        *arrayOf<Any>("actual size less than 0")
                    )
                    return false
                }
            } else {
                RFWLog.b(
                    "RFWMatrixImageView",
                    RFWLog.e,
                    *arrayOf<Any>("displayHeight is less than 0f")
                )
                return false
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (Utils.isRoundScreen) {
            setPadding(size, size, size, size)
        }
    }
}