package momoi.mod.qqpro.hook

import android.app.Dialog
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.Window
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.tencent.watch.aio_impl.ui.frames.SettingFrame
import momoi.mod.qqpro.lib.FILL
import momoi.mod.qqpro.lib.WRAP
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.material.M3Button
import momoi.mod.qqpro.hook.style.cardMargin
import momoi.mod.qqpro.util.ChatBackground
import momoi.mod.qqpro.util.Utils

/** 聊天背景入口（右滑聊天设置页）的图片选择请求码，由 SettingFrame 的 onActivityResult 处理。 */
const val REQ_CHAT_BG_PICK = 0x9C01
const val CHAT_BG_ENTRY_LABEL = "聊天背景"

/**
 * 在右滑聊天设置页加入「聊天背景」入口：点击弹出选项（设置背景=选图进裁剪页 / 清除），
 * 只对当前会话生效（peer 取自 SettingFrame 参数）。总开关关闭时不显示。
 */
fun addChatBgEntry(fragment: SettingFrame) {
    runCatching {
        if (!ChatBackground.enabled()) return
        val scroll = fragment.i ?: return
        val container = scroll.getChildAt(0) as? LinearLayout ?: return
        val ctx = fragment.requireContext()
        val res = ctx.resources
        val pkg = ctx.packageName
        val descId = res.getIdentifier("desc", "id", pkg)
        // 重复挂载保护（onViewCreated 可能重入）。
        for (i in 0 until container.childCount) {
            val desc = container.getChildAt(i).findViewById<TextView>(descId)
            if (desc?.text?.toString() == CHAT_BG_ENTRY_LABEL) return
        }
        val layoutId = res.getIdentifier("setting_item", "layout", pkg)
        if (layoutId == 0) return
        val row = LayoutInflater.from(ctx).inflate(layoutId, container, false)
        row.findViewById<ImageView>(res.getIdentifier("icon", "id", pkg))?.let { iv ->
            runCatching { iv.setImageDrawable(momoi.mod.qqpro.lib.material.MaterialSymbol(momoi.mod.qqpro.lib.material.MaterialSymbols.photo_camera, M3.primary)) }
        }
        row.findViewById<TextView>(descId)?.text = CHAT_BG_ENTRY_LABEL
        row.setOnClickListener { showChatBgOptions(fragment) }
        container.addView(row, minOf(4, container.childCount))
        row.cardMargin()
        Utils.log("ChatBgEntry: 右滑页加入「聊天背景」入口")
    }.onFailure { Utils.log("ChatBgEntry: add failed: $it") }
}

private fun showChatBgOptions(fragment: SettingFrame) {
    val ctx = fragment.requireContext()
    val dialog = Dialog(ctx)
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
    val col = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(20.dp, 18.dp, 20.dp, 16.dp)
    }
    col.addView(TextView(ctx).apply {
        text = "聊天背景"
        textSize = 16f
        setTextColor(M3.onSurface)
        gravity = Gravity.CENTER
    }, LinearLayout.LayoutParams(FILL, WRAP))

    col.addView(M3Button(ctx).variant(M3Button.Variant.FILLED).apply {
        text = "设置背景（选图裁剪）"
        setOnClickListener {
            dialog.dismiss()
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            runCatching {
                fragment.startActivityForResult(Intent.createChooser(intent, "选择背景图片"), REQ_CHAT_BG_PICK)
            }.onFailure { Utils.log("ChatBgEntry: picker failed: $it") }
        }
    }, LinearLayout.LayoutParams(FILL, WRAP).apply { topMargin = 12.dp })

    col.addView(M3Button(ctx).variant(M3Button.Variant.TEXT).apply {
        text = "清除背景"
        setOnClickListener {
            dialog.dismiss()
            val peer = fragment.arguments?.getString("key_bundle_peer_id") ?: return@setOnClickListener
            ChatBackground.clear(peer)
            Utils.toast(ctx, "已清除该会话背景")
        }
    }, LinearLayout.LayoutParams(FILL, WRAP).apply { topMargin = 8.dp })

    dialog.setContentView(col)
    dialog.window?.setBackgroundDrawable(ColorDrawable(M3.surface))
    dialog.show()
}
