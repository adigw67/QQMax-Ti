package momoi.mod.qqpro.hook.imageeditor

import android.annotation.SuppressLint
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.fragment.app.FragmentManager
import momoi.mod.qqpro.hook.view.MyDialogFragment
import momoi.mod.qqpro.lib.bitmapDecodeFile
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3Button
import momoi.mod.qqpro.util.Utils
import java.io.File

/** Entry helper for the image editor — opens [ImageEditorFragment] and delivers the edited file. */
object ImageEditor {
    fun open(fm: FragmentManager, file: File, onDone: (File) -> Unit) {
        runCatching { ImageEditorFragment(file, onDone).show(fm, "qqpro_image_editor") }
            .onFailure { Utils.log("ImageEditor.open failed: $it") }
    }
}

/**
 * The pre-send preview shown (when the 发送前预览单图 setting is on) after picking a single image: the
 * picture full-screen with a 编辑 / 发送 choice bar. 发送 sends the original; 编辑 opens the editor first.
 */
@SuppressLint("ValidFragment")
class SendPreviewFragment(
    private val path: String = "",
    private val onSend: () -> Unit = {},
    private val onEdit: () -> Unit = {},
) : MyDialogFragment() {

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val ctx = inflater.context
        val root = FrameLayout(ctx).apply { background = ColorDrawable(M3.surface) }

        val image = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            runCatching { bitmapDecodeFile(File(path)) }.onFailure { Utils.log("preview decode: $it") }
        }
        root.addView(image, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT).apply {
            bottomMargin = 64.dp
        })

        val bar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(16.dp, 8.dp, 16.dp, 14.dp)
        }
        bar.addView(M3Button(ctx).variant(M3Button.Variant.TONAL).apply {
            text = "编辑"; setOnClickListener { onEdit(); dismissAllowingStateLoss() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = 8.dp })
        bar.addView(M3Button(ctx).variant(M3Button.Variant.FILLED).apply {
            text = "发送"; setOnClickListener { onSend(); dismissAllowingStateLoss() }
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = 8.dp })
        root.addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM))
        return root
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
    }
}
