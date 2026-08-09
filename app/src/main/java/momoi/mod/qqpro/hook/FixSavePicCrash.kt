package momoi.mod.qqpro.hook

import android.content.Context
import androidx.core.util.Consumer
import com.tencent.biz.richframework.util.RFWSaveUtil
import com.tencent.biz.richframework.util.bean.RFWSaveMediaResultBean
import momoi.anno.mixin.StaticHook
import momoi.mod.qqpro.util.Utils

/**
 * Saving a picture from the long-press menu crashes on this watch ROM:
 *   Resources$NotFoundException: String resource ID #0x0
 * QQ's RFWSaveUtil.a() resolves the album sub-directory name via
 * RFWAppUtil.appName, whose lazy initializer calls
 * Resources.getString(applicationInfo.labelRes). On this device labelRes is 0,
 * and the lazy only catches NameNotFoundException, so the crash propagates.
 *
 * Bypass the broken lazy: resolve the app label safely ourselves
 * (PackageManager.getApplicationLabel falls back to the package name when
 * labelRes is 0) and call RFWSaveUtil.b directly with it.
 */
@StaticHook(RFWSaveUtil::class)
fun a(
    context: Context,
    mediaPath: String,
    consumer: Consumer<RFWSaveMediaResultBean>?,
) {
    val appName = try {
        val pm = context.packageManager
        pm.getApplicationLabel(context.applicationInfo).toString()
    } catch (e: Throwable) {
        Utils.log("FixSavePicCrash: getApplicationLabel failed: ${e.message}")
        "QQ"
    }
    RFWSaveUtil.b(context, mediaPath, appName, consumer)
}
