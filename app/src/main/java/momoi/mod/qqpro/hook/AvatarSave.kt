package momoi.mod.qqpro.hook

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import com.tencent.biz.richframework.util.RFWSaveUtil
import com.tencent.qqnt.msg.KernelServiceUtil
import download
import momoi.mod.qqpro.child
import momoi.mod.qqpro.hook.qzone.MediaSave
import momoi.mod.qqpro.safeCacheDir
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.runOnUi
import java.io.File

/**
 * 长按头像保存到相册:给资料卡 / 群·单聊设置页里 **复用的原生头像视图** 绑定长按手势,长按即把该
 * 用户头像或群头像下载大图后保存到系统相册。
 *
 * 普通(非 @Mixin)对象:内部创建的匿名类(OnLongClickListener / 下载回调 lambda)生成在本包,不会被
 * ApkMixin 拷贝进目标包,避免匿名类构造器跨包不可访问的 IllegalAccessError(见 qqpro-mixin-anon-class)。
 * 公开可见,以便从 @Mixin 体([ProfileCardIconFix] / [GroupAvatarPreview])调用。
 */
object AvatarSave {

    /** 用户头像大图(spec=640 比页面里 48~64dp 的小图清晰得多)。uin 非数字时返回 null。 */
    fun userUrl(uin: String?): String? =
        uin?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let {
            "https://q.qlogo.cn/headimg_dl?dst_uin=$it&spec=640"
        }

    /** 群头像大图(/0 为原始尺寸)。groupCode 非数字时返回 null。 */
    fun groupUrl(groupCode: String?): String? =
        groupCode?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let {
            "https://p.qlogo.cn/gh/$it/$it/0"
        }

    /**
     * 设置页头像 URL:群聊(chatType==2)用群号取群头像;单聊用 peerId 解析出 uin 取用户头像
     * (peerId 在单聊里通常是 uid,需经 UixConvert 转 uin;若本身已是数字则直接当 uin 用)。
     */
    fun contactUrl(chatType: Int, peerId: String?): String? {
        if (peerId.isNullOrEmpty()) return null
        if (chatType == 2) return groupUrl(peerId)
        val uin = peerId.trim().toLongOrNull()?.toString() ?: uidToUin(peerId)
        return userUrl(uin)
    }

    private fun uidToUin(uid: String): String? = runCatching {
        KernelServiceUtil.f()?.uixConvertService?.y(uid)?.takeIf { it > 0L }?.toString()
    }.onFailure { Utils.log("AvatarSave.uidToUin failed for $uid: ${it.message}") }.getOrNull()

    /**
     * 给头像视图绑定"长按保存"。[urlProvider] 返回头像大图 URL(在长按时惰性求值,以便届时再做 uid→uin
     * 解析);返回 null 或下载失败时,退化为直接把当前显示的头像视图截图保存。
     */
    fun attach(avatar: View?, urlProvider: () -> String?) {
        avatar ?: return
        avatar.isLongClickable = true
        avatar.setOnLongClickListener { v ->
            val ctx = v.context
            Utils.toast(ctx, "保存中…")
            val url = runCatching { urlProvider() }.getOrNull()
            if (url != null) {
                val dir = ctx.safeCacheDir
                if (dir == null) { captureAndSave(ctx, v); return@setOnLongClickListener true }
                val tmp = dir.child("qqpro_avatar_${System.currentTimeMillis()}.tmp")
                download(url, tmp) { ok ->
                    runOnUi {
                        if (ok && tmp.length() > 0) saveDownloaded(ctx, tmp)
                        else captureAndSave(ctx, v)
                    }
                }
            } else {
                captureAndSave(ctx, v)
            }
            true
        }
    }

    /** 嗅探下载文件的真实格式,改成正确后缀(否则相册可能把 png/gif 当 jpg 处理),再存入相册。 */
    private fun saveDownloaded(ctx: Context, tmp: File) {
        val file = runCatching {
            val ext = MediaSave.imageTypeOf(tmp).first
            val named = File(tmp.parentFile, "avatar_${System.currentTimeMillis()}.$ext")
            if (tmp.renameTo(named)) named else tmp
        }.getOrDefault(tmp)
        saveToAlbum(ctx, file)
    }

    /** 退化方案:把当前显示的头像视图(已是圆形/裁切后的样子)截图为 PNG 保存。 */
    private fun captureAndSave(ctx: Context, v: View) {
        runCatching {
            if (v.width <= 0 || v.height <= 0) { Utils.toast(ctx, "保存失败"); return }
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            val dir = ctx.safeCacheDir ?: run { Utils.toast(ctx, "保存失败"); return }
            val file = dir.child("avatar_${System.currentTimeMillis()}.png")
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            saveToAlbum(ctx, file)
        }.onFailure { Utils.log("AvatarSave.capture: $it"); Utils.toast(ctx, "保存失败") }
    }

    private fun saveToAlbum(ctx: Context, file: File) {
        runCatching {
            RFWSaveUtil.a(ctx, file.path, null)
            Utils.toast(ctx, "已保存到相册")
        }.onFailure { Utils.log("AvatarSave.saveToAlbum: $it"); Utils.toast(ctx, "保存失败") }
    }
}
