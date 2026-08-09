package momoi.mod.qqpro.hook.style

import com.tencent.biz.qui.quicommon.ViewUtils
import com.tencent.mobileqq.quibadge.QUIBadge
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.lib.material.M3

@Mixin
class 具体未读数 : QUIBadge(null, null) {
    override fun f(i: Int) {
        this.j = i
        // 超过 999 统一显示 999+，避免数字过长溢出裁切
        this.k = if (i > 999) "999+" else i.toString()
    }

    override fun getMinWidth(): Int {
        // viewType 2/3 (红/灰数字角标) 原本对 >99 的情况固定 31dp（只够 "99+"），
        // "999+" 等更长文本会被裁切。这里按实际文本宽度测量来撑开。
        val i = this.i
        if ((i == 2 || i == 3) && this.j > 99) {
            val textWidth = this.m.measureText(this.k).toInt() + getResource().b() * 2
            return maxOf(textWidth, ViewUtils.a(31f))
        }
        return super.getMinWidth()
    }

    override fun setRedNum(i: Int) {
        super.setRedNum(i)
        if (Settings.materialChatList.value) n.color = M3.badge
    }

    override fun setGrayNum(i: Int) {
        super.setGrayNum(i)
        if (Settings.materialChatList.value) n.color = M3.surfaceVariant
    }
}
