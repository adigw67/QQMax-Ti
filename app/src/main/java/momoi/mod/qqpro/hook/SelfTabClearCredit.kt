package momoi.mod.qqpro.hook

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.tencent.qqnt.watch.selftab.ui.SelfFragment
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.lib.onEachLayout
import momoi.mod.qqpro.lib.replaceTextRecursive
import momoi.mod.qqpro.util.ChatBackground
import momoi.mod.qqpro.util.Utils

/**
 * The self tab (outer-most list) shows a base-app credit line at the bottom. The real about
 * content now lives in the dedicated 关于 dialog ([AboutItemHook]), so blank this line out.
 *
 * The base app's resources still brand the settings/about entries as "QQPro" (baked into
 * resources.arsc, which the mixin build can't recompile). Rewrite that to "QQMax-Ti" at
 * runtime on each layout pass — the entry rows bind asynchronously in a RecyclerView, so a
 * one-shot pass in Y() would miss them.
 */
@Mixin
class SelfTabClearCredit : SelfFragment() {
    @SuppressLint("ResourceType")
    override fun Y(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        val result = super.Y(inflater, container, savedInstanceState)
        // 其他界面背景：挂到背景层，并让本页根透明露出背景图。
        if (ChatBackground.pagesEnabled()) {
            runCatching {
                ChatBackground.applyToPages(this)
                result.setBackgroundColor(0)
            }.onFailure { Utils.log("SelfTabClearCredit pages bg: $it") }
        }
        result.findViewById<TextView>(2114521808)?.text = ""
        result.onEachLayout {
            result.replaceTextRecursive("QQPro", "QQMax-Ti")
            result.replaceTextRecursive("NWear-QQ", "")
            result.replaceTextRecursive("NWearQQ", "")
        }
        return result
    }
}
