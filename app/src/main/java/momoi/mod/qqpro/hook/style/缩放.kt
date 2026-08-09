package momoi.mod.qqpro.hook.style

import me.jessyan.autosize.AutoSizeConfig
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings

@Mixin
class 缩放 : AutoSizeConfig() {
    override fun getDesignHeightInDp(): Int {
        return (super.getDesignHeightInDp() / Settings.scale.value).toInt()
    }

    override fun getDesignWidthInDp(): Int {
        return (super.getDesignWidthInDp() / Settings.scale.value).toInt()
    }
}