package momoi.mod.qqpro.hook.screenshot

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.tencent.biz.richframework.util.RFWSaveUtil
import com.tencent.qqnt.kernel.nativeinterface.Contact
import com.tencent.qqnt.kernel.nativeinterface.IOperateCallback
import com.tencent.qqnt.watch.contact.api.IContactRuntimeService
import WatchPicElementExtKt
import momoi.mod.qqpro.MsgUtil
import momoi.mod.qqpro.child
import momoi.mod.qqpro.hook.action.CurrentContact
import momoi.mod.qqpro.lib.FILL
import momoi.mod.qqpro.lib.SwipeBackLayout
import momoi.mod.qqpro.lib.WRAP
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3Button
import momoi.mod.qqpro.safeCacheDir
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.runOnUi
import mqq.app.MobileQQ
import java.io.File

/**
 * Fullscreen preview of a generated chat screenshot. The (possibly very tall) bitmap and the action
 * buttons live in ONE vertical column inside a ScrollView, so everything scrolls together and the
 * buttons stack vertically (better than a fixed 3-up bar on a tiny watch). Right-swipe dismisses.
 * 发送 opens the forward (friend-selector) dialog; 保存 writes to the gallery. PNG encoding is off-thread.
 */
object ScreenshotPreview {

    fun show(ctx: Context, bmp: Bitmap, host: View) {
        val dialog = Dialog(ctx)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)

        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(M3.surface)
            setPadding(8.dp, 8.dp, 8.dp, 16.dp)
        }
        col.addView(ImageView(ctx).apply {
            setImageBitmap(bmp)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(FILL, WRAP))

        fun addButton(label: String, variant: M3Button.Variant, onTap: () -> Unit) {
            col.addView(M3Button(ctx).apply {
                text = label
                variant(variant)
                setOnClickListener { onTap() }
            }, LinearLayout.LayoutParams(FILL, 44.dp).apply { topMargin = 8.dp })
        }
        addButton("发送", M3Button.Variant.FILLED) {
            Utils.toast(ctx, "处理中…")
            writeCache(ctx, bmp) { f ->
                if (f == null) { Utils.toast(ctx, "失败"); return@writeCache }
                dialog.dismiss()
                forwardImageFile(host, f)
            }
        }
        addButton("保存", M3Button.Variant.TONAL) { save(ctx, bmp) }
        addButton("取消", M3Button.Variant.TEXT) { dialog.dismiss() }

        val scroll = ScrollView(ctx).apply {
            isFillViewport = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(col, FILL, WRAP)
        }
        val root = SwipeBackLayout(ctx).apply {
            onSwipeBack = { dialog.dismiss() }
            addView(scroll, FILL, FILL)
        }

        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(M3.surface))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        dialog.show()
    }

    /** Encode [bmp] to a PNG in the cache dir off the UI thread; [cb] runs back on the UI thread. */
    private fun writeCache(ctx: Context, bmp: Bitmap, cb: (File?) -> Unit) {
        Thread {
            val f = runCatching {
                val dir = ctx.safeCacheDir ?: return@runCatching null
                val file = dir.child("qqpro_shot_${System.currentTimeMillis()}.png")
                file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                file
            }.getOrElse { Utils.log("screenshot writeCache failed: $it"); null }
            runOnUi { cb(f) }
        }.start()
    }

    private fun save(ctx: Context, bmp: Bitmap) {
        Utils.toast(ctx, "保存中…")
        writeCache(ctx, bmp) { f ->
            if (f == null) { Utils.toast(ctx, "保存失败"); return@writeCache }
            runCatching { RFWSaveUtil.a(ctx, f.path, null); Utils.toast(ctx, "已保存到相册") }
                .onFailure { Utils.log("screenshot save failed: $it"); Utils.toast(ctx, "保存失败") }
        }
    }
}

/**
 * Forward an image [file] via the friend selector (转发 dialog), then send it to each chosen target.
 * Mirrors batchForward (ChatMultiSelectActions). [host] must be a View in the chat's fragment nav tree
 * (the chat RecyclerView) so the selector can be pushed onto the nav stack.
 */
fun forwardImageFile(host: View, file: File) {
    val ctx = host.context
    val navFragment = WatchPicElementExtKt.W(host)?.let { WatchPicElementExtKt.Y(it) }
        ?: run { Utils.log("forwardImageFile: no nav fragment"); Utils.toast(ctx, "无法转发"); return }
    val app = MobileQQ.getMobileQQ().peekAppRuntime() ?: run { Utils.toast(ctx, "无法转发"); return }
    val contactService = app.getRuntimeService(IContactRuntimeService::class.java, "")
    contactService.startFriendSelect(
        navFragment, emptyList(), arrayListOf(app.currentUid),
        "转发截图", 0x7e0805cd, 1, 10, null, false, true
    ) { _, friends ->
        if (friends.isNotEmpty()) Thread {
            friends.forEach { friend ->
                runCatching {
                    val element = com.tencent.watch.aio_impl.ext.MsgUtil().a(file.path, 0)
                    val dst = Contact(if (friend.e) 2 else 1, friend.b, "")
                    MsgUtil.msgService.sendMsg(dst, 0L, arrayListOf(element),
                        IOperateCallback { code, m -> Utils.log("screenshot forward result=$code peer=${dst.peerUid} msg=$m") })
                }.onFailure { Utils.log("screenshot forward send failed: $it") }
            }
        }.start()
        kotlin.Unit
    }
}
