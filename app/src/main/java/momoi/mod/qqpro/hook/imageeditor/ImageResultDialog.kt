package momoi.mod.qqpro.hook.imageeditor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import momoi.mod.qqpro.hook.qzone.MediaSave
import momoi.mod.qqpro.hook.shareImageFile
import momoi.mod.qqpro.hook.view.MyDialogFragment
import momoi.mod.qqpro.lib.FILL
import momoi.mod.qqpro.lib.LinearScope
import momoi.mod.qqpro.lib.clickable
import momoi.mod.qqpro.lib.content
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.gravity
import momoi.mod.qqpro.lib.margin
import momoi.mod.qqpro.lib.padding
import momoi.mod.qqpro.lib.text
import momoi.mod.qqpro.lib.textColor
import momoi.mod.qqpro.lib.textSize
import momoi.mod.qqpro.lib.vertical
import momoi.mod.qqpro.lib.width
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.runOnUi
import java.io.File

/**
 * Shown after the image editor finishes (✓): lets the user choose what to do with the edited image —
 * 保存 (to the gallery) / 系统分享 (out to another app) / 转发 (to a QQ contact) / 取消.
 *
 * 保存 and 系统分享 are generic (file-based) and handled here; 转发 is QQ-specific (it needs the nav
 * fragment the caller captured before opening the editor) so it is passed in as [onForward].
 * Styling mirrors [momoi.mod.qqpro.hook.view.ConfirmFragment].
 */
@SuppressLint("ValidFragment")
class ImageResultDialog(
    private val file: File = File(""),
    private val onForward: () -> Unit = {},
) : MyDialogFragment() {
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = inflater.context
        val root = LinearLayout(ctx).vertical().padding(20.dp)
        root.gravity = Gravity.CENTER
        root.content {
            add<TextView>()
                .text("图片已编辑")
                .textSize(15f)
                .textColor(M3.onSurface)
                .gravity(Gravity.CENTER)
                .padding(bottom = 16.dp)
            button("保存", M3.primary, M3.onColor(M3.primary)) { dismiss(); saveToGallery(ctx.applicationContext) }
            // Share needs a live View; do it before dismissing (view detaches on dismiss).
            button("系统分享", M3.surfaceContainerHigh, M3.onSurface) { view?.shareImageFile(file); dismiss() }
            button("转发", M3.surfaceContainerHigh, M3.onSurface) { dismiss(); runCatching { onForward() } }
            button("取消", M3.surfaceContainerHigh, M3.onSurface) { dismiss() }
        }
        val scroll = ScrollView(ctx).apply {
            setBackgroundColor(M3.surface)
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        return swipeBackWrap(scroll)
    }

    private fun saveToGallery(appCtx: Context) {
        Thread {
            val (ext, mime) = MediaSave.imageTypeOf(file)
            val name = "QQMaxTi_${System.currentTimeMillis()}.$ext"
            val ok = MediaSave.toGallery(appCtx, file, name, mime, isVideo = false, subDir = "QQMax-Ti")
            runOnUi { Utils.toast(Utils.application, if (ok) "已保存到相册" else "保存失败") }
        }.start()
    }

    private fun LinearScope.button(label: String, bg: Int, fg: Int, onClick: () -> Unit) {
        add<TextView>()
            .text(label)
            .textSize(14f)
            .textColor(fg)
            .gravity(Gravity.CENTER)
            .width(FILL)
            .padding(top = 10.dp, bottom = 10.dp)
            .apply {
                background = GradientDrawable().apply { setColor(bg); cornerRadius = 22.dp.toFloat() }
            }
            .margin(top = 6.dp)
            .clickable(onClick)
    }
}
