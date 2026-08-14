package momoi.mod.qqpro.hook

import android.widget.ImageView
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.lib.material.M3
import momoi.mod.qqpro.util.ChatBackground
import momoi.mod.qqpro.util.Utils

/**
 * 聊天背景应用器（普通 object，不是 @Mixin 类——避免跨 mixin 引用 companion 在运行时为空导致
 * 打开群聊 NPE）。WatchAIOFragment.onViewCreated 记录背景 ImageView，ChatPie 钩子拿到本会话
 * 真实 peer 后调用 [applyPeerBackground] 重挂，解决背景串问题。
 */
object ChatBgApplier {

    @Volatile
    var lastBgView: ImageView? = null

    /**
     * 按真实会话 peer 应用聊天背景（独立背景优先，其次全局；都没有则还原为 M3 surface）。
     */
    fun applyPeerBackground(peerUid: String?) {
        val bg = lastBgView ?: return
        runCatching {
            val peer = peerUid?.takeIf { it.isNotBlank() }
            if (ChatBackground.enabled() && ChatBackground.isSet(peer)) {
                Utils.log("ChatBgApplier peer=$peer")
                ChatBackground.applyTo(bg, peer)
            } else if (Settings.materializeChat.value) {
                bg.setImageDrawable(null)
                bg.setBackgroundColor(M3.surface)
            }
        }.onFailure { Utils.log("ChatBgApplier failed: $it") }
    }
}
