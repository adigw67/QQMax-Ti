package momoi.mod.qqpro.hook

import com.tencent.qqnt.kernel.nativeinterface.RecentContactInfo
import com.tencent.qqnt.watch.dirty.api.impl.DirtyMsgApiImpl
import momoi.anno.mixin.Mixin

/**
 * The recent/conversation list preview text for "dirty" message types
 * (file/ark/multi-forward/struct…) is produced by
 * [DirtyMsgApiImpl.getToQQViewAbstract], which returns the generic
 * "该消息请在手机QQ查看" placeholder. Now that these types are rendered in-chat,
 * relabel the list preview with a proper tag instead of the phone-only tip.
 *
 * Only the generic tip is replaced — red-packet / flash-pic / map-share wording
 * (which the original returns for those cases) is preserved by deferring to
 * super and only rewriting when it returned the generic placeholder.
 */
@Mixin
class DirtyMsgAbstract : DirtyMsgApiImpl() {
    override fun getToQQViewAbstract(recentContactInfo: RecentContactInfo): CharSequence? {
        val original = super.getToQQViewAbstract(recentContactInfo)
        if (original != TO_QQ_VIEW_TIPS) return original
        val type = recentContactInfo.abstractContent?.firstOrNull {
            it.elementType == 16 || it.elementType == 10 ||
                it.elementType == 3 || it.elementType == 40
        }?.elementType ?: return original
        return when (type) {
            16 -> "[聊天记录]"
            10 -> "[卡片]"
            3 -> "[文件]"
            40 -> "[群邀请]"
            else -> original
        }
    }

    companion object {
        private const val TO_QQ_VIEW_TIPS = "该消息请在手机QQ查看"
    }
}
