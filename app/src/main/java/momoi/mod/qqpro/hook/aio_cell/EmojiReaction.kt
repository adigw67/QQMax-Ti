package momoi.mod.qqpro.hook.aio_cell

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentManager
import com.tencent.qqnt.emotion.utils.QQSysFaceUtil
import com.tencent.qqnt.kernel.nativeinterface.Contact
import com.tencent.qqnt.kernel.nativeinterface.IGProSetMsgEmojiLikesCallback
import com.tencent.qqnt.kernel.nativeinterface.IOperateCallback
import com.tencent.qqnt.kernel.nativeinterface.MsgElement
import com.tencent.qqnt.kernel.nativeinterface.MsgRecord
import com.tencent.qqnt.msg.KernelServiceUtil
import com.tencent.qqnt.msg.api.impl.MsgUtilApiImpl
import momoi.mod.qqpro.MsgUtil
import momoi.mod.qqpro.hook.view.MyDialogFragment
import momoi.mod.qqpro.lib.FILL
import momoi.mod.qqpro.lib.WRAP
import momoi.mod.qqpro.lib.dp
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.lib.vertical
import momoi.mod.qqpro.util.ThreadManager
import momoi.mod.qqpro.util.Utils
import momoi.mod.qqpro.util.runOnUi
import kotlin.concurrent.thread

/**
 * 表情回应（消息表态）——使用 QQ 自带系统表情列表，不是 Unicode emoji。
 *
 * 长按菜单「表情回应」→ [EmojiReactionFragment]：QQ 系统表情网格（与输入框内嵌表情面板同一套
 * 数据源 [QQSysFaceUtil]），点一个表情：
 *   1. 优先走表态接口 [com.tencent.qqnt.kernel.nativeinterface.IKernelMsgService.setMsgEmojiLikes]；
 *   2. 手表产品的 oidb 对表态 cmd 无权限（实测 -10122 Product does not have permission to access cmd，
 *      与禁言同款），此时自动降级为“带回复引用的表情消息”发出（普通消息发送有权限），
 *      对方同样能看到这条表情回应。
 *
 * 消息气泡下展示已有表态：表情小图 + 数量，点击切换本人表态。
 */
object EmojiReaction {

    private const val TAG_REACTION_ROW = "qqpro_reaction_row"
    private const val ERR_PRODUCT_NO_PERMISSION = -10122

    /** 打开表情列表面板（长按菜单入口）。 */
    fun showPanel(host: View, msg: MsgRecord, fm: FragmentManager) {
        runCatching {
            EmojiReactionFragment(msg).show(fm, "qqpro_emo_reply")
        }.onFailure { Utils.log("EmojiReaction: open panel failed: $it") }
    }

    /** 服务端 faceIndex → 本地表情 id（-1/异常时返回 null）。 */
    private fun localFaceId(serverIndex: Int?): Int? =
        serverIndex?.let { runCatching { QQSysFaceUtil.a.a(it) }.getOrNull()?.takeIf { id -> id > 0 } }

    /** 本地表情 id → 服务端 faceIndex（用于发送）。 */
    private fun serverFaceId(localId: Int): Int =
        runCatching { QQSysFaceUtil.a.b(localId) }.getOrDefault(localId)

    /** 服务端 faceIndex → 表情 Drawable（失败返回 null）。 */
    private fun faceDrawable(serverIndex: Int?): Drawable? {
        val local = localFaceId(serverIndex) ?: return null
        return runCatching { QQSysFaceUtil.a.d(local) }.getOrNull()
    }

    /** 发送/取消表态。onDone(ok, code) 在 UI 线程回调。 */
    fun setReaction(msg: MsgRecord, faceIndex: Int, isSet: Boolean, onDone: (Boolean, Int) -> Unit = { _, _ -> }) {
        val raw = runCatching { KernelServiceUtil.g()?.getMsgService() }.getOrNull()
        if (raw == null) {
            Utils.log("EmojiReaction: no msg service")
            runOnUi { onDone(false, -1) }
            return
        }
        val contact = runCatching { Contact(msg.chatType, msg.peerUid, "") }.getOrNull()
        if (contact == null) {
            runOnUi { onDone(false, -1) }
            return
        }
        runCatching {
            raw.setMsgEmojiLikes(
                contact, msg.msgSeq, faceIndex.toString(), 1L, isSet,
                object : IGProSetMsgEmojiLikesCallback {
                    override fun onSetMsgEmojiLikes(code: Int, err: String?) {
                        Utils.log("EmojiReaction: set face=$faceIndex set=$isSet code=$code err=$err")
                        runOnUi { onDone(code == 0, code) }
                    }
                }
            )
        }.onFailure {
            Utils.log("EmojiReaction: set threw: $it")
            runOnUi { onDone(false, -1) }
        }
    }

    /** 降级方案：发一条“带引用原消息的表情消息”。 */
    fun sendFaceReply(msg: MsgRecord, faceIndex: Int, onDone: (Boolean) -> Unit = {}) {
        val contact = runCatching { Contact(msg.chatType, msg.peerUid, "") }.getOrNull()
        if (contact == null) {
            runOnUi { onDone(false) }
            return
        }
        val elements = ArrayList<MsgElement>()
        runCatching {
            if (msg.msgId != 0L) {
                MsgUtilApiImpl.instance.createReplyElement(msg.msgId)?.let { elements.add(it) }
            }
            MsgUtilApiImpl.instance.createFaceElement(faceIndex, 1, "")?.let { elements.add(it) }
        }.onFailure { Utils.log("EmojiReaction: build face msg failed: $it") }
        if (elements.isEmpty()) {
            runOnUi { onDone(false) }
            return
        }
        runCatching {
            MsgUtil.msgService.sendMsg(contact, 0L, elements, IOperateCallback { code, err ->
                Utils.log("EmojiReaction: face reply send code=$code err=$err")
                runOnUi { onDone(code == 0) }
            })
        }.onFailure {
            Utils.log("EmojiReaction: face reply send threw: $it")
            runOnUi { onDone(false) }
        }
    }

    /**
     * 在消息气泡下附加表态行：每个表态一个圆片（表情小图 + 数量），点击切换本人表态。
     * RecyclerView 复用时先移除旧行再重建，幂等。
     */
    fun attach(root: View?, msg: MsgRecord) {
        root ?: return
        if (root !is ViewGroup) return
        val likes = runCatching { msg.emojiLikesList }.getOrNull().orEmpty()
        runCatching {
            root.findViewWithTag<View>(TAG_REACTION_ROW)?.let {
                (it.parent as? ViewGroup)?.removeView(it)
            }
            if (likes.isEmpty()) return
            val ctx = root.context
            val row = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                tag = TAG_REACTION_ROW
                setPadding(10.dp, 2.dp, 10.dp, 4.dp)
            }
            likes.forEach { like ->
                val faceIndex = like.emojiId?.toIntOrNull()
                val chip = TextView(ctx).apply {
                    text = " ${like.likesCnt}"
                    textSize = 12f
                    gravity = Gravity.CENTER
                    val bg = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 12.dp.toFloat()
                        setColor(if (like.isClicked) M3.primaryContainer else M3.surfaceContainerHigh)
                    }
                    background = bg
                    setTextColor(if (like.isClicked) M3.onPrimaryContainer else M3.onSurfaceVariant)
                    setPadding(8.dp, 3.dp, 8.dp, 3.dp)
                    faceDrawable(faceIndex)?.let {
                        setCompoundDrawablesWithIntrinsicBounds(it, null, null, null)
                        compoundDrawablePadding = 4.dp
                    }
                    setOnClickListener {
                        val fi = faceIndex ?: return@setOnClickListener
                        setReaction(msg, fi, !like.isClicked) { ok, code ->
                            if (ok) {
                                if (like.isClicked) {
                                    like.likesCnt = (like.likesCnt - 1).coerceAtLeast(0)
                                    like.isClicked = false
                                } else {
                                    like.likesCnt = like.likesCnt + 1
                                    like.isClicked = true
                                }
                                attach(root, msg)
                            } else if (code == ERR_PRODUCT_NO_PERMISSION) {
                                Utils.toast(ctx, "表态无权限，改为发送表情消息")
                                sendFaceReply(msg, fi)
                            } else {
                                Utils.toast(ctx, "表态失败")
                            }
                        }
                    }
                }
                row.addView(chip, LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = 6.dp })
            }
            root.addView(row, ViewGroup.LayoutParams(FILL, WRAP))
        }.onFailure { Utils.log("EmojiReaction.attach failed: $it") }
    }

    /** 表情列表面板：QQ 自带系统表情网格，点击即表态（权限不足时降级为表情消息）。 */
    class EmojiReactionFragment(private val msg: MsgRecord) : MyDialogFragment() {

        override fun onCreateView(
            inflater: android.view.LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
        ): View {
            val ctx = requireContext()
            val root = LinearLayout(ctx).vertical()
            root.setBackgroundColor(M3.surface)

            root.addView(TextView(ctx).apply {
                text = "选择表情回应"
                textSize = 15f
                setTextColor(M3.onSurface)
                gravity = Gravity.CENTER
                setPadding(0, 12.dp, 0, 8.dp)
            }, LinearLayout.LayoutParams(FILL, WRAP))

            val gridHolder = ScrollView(ctx).apply { isFillViewport = true }
            root.addView(gridHolder, LinearLayout.LayoutParams(FILL, 0, 1f))
            val grid = GridLayout(ctx).apply {
                columnCount = 6
                setPadding(6.dp, 6.dp, 6.dp, 6.dp)
            }
            gridHolder.addView(grid)

            val screenW = ctx.resources.displayMetrics.widthPixels
            val cell = ((screenW - 12.dp) / 6).coerceAtLeast(24.dp)
            val pad = (cell * 0.16f).toInt()

            val progress = TextView(ctx).apply {
                text = "加载中…"
                textSize = 13f
                setTextColor(M3.onSurfaceVariant)
                gravity = Gravity.CENTER
            }
            root.addView(progress, LinearLayout.LayoutParams(FILL, WRAP).apply { topMargin = 24.dp })

            // 后台收集可用表情（解码较慢），分批上屏。
            thread {
                val all = runCatching { QQSysFaceUtil.a.h() }.getOrNull().orEmpty()
                val ids = ArrayList<Int>(all.size)
                for (k in 0 until all.size) {
                    val id = all[k] ?: continue
                    if (runCatching { QQSysFaceUtil.a.j(id) }.getOrDefault(false)) ids.add(id)
                }
                val faces = ArrayList<Pair<Int, Drawable>>(ids.size)
                for (id in ids) {
                    val d = runCatching { QQSysFaceUtil.a.d(id) }.getOrNull() ?: continue
                    faces.add(id to d)
                }
                ThreadManager.runOnUiThread(Runnable {
                    if (!isAdded) return@Runnable
                    root.removeView(progress)
                    for ((id, d) in faces) {
                        val iv = ImageView(ctx).apply {
                            setImageDrawable(d)
                            setPadding(pad, pad, pad, pad)
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            setOnClickListener {
                                pick(serverFaceId(id))
                            }
                        }
                        grid.addView(iv, GridLayout.LayoutParams().apply { width = cell; height = cell })
                    }
                })
            }
            return swipeBackWrap(root)
        }

        /** 点了一个 QQ 表情：先表态；产品无权限则降级为表情回复消息。 */
        private fun pick(serverId: Int) {
            setReaction(msg, serverId, true) { ok, code ->
                if (ok) {
                    Utils.toast(requireContext(), "已表态")
                    dismiss()
                } else if (code == ERR_PRODUCT_NO_PERMISSION) {
                    Utils.toast(requireContext(), "表态无权限，改为发送表情消息")
                    sendFaceReply(msg, serverId) { sent ->
                        if (!sent) Utils.toast(requireContext(), "发送失败")
                        dismiss()
                    }
                } else {
                    Utils.toast(requireContext(), "表态失败")
                    dismiss()
                }
            }
        }
    }
}
