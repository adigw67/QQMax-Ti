package momoi.mod.qqpro.hook

import android.view.View
import android.view.ViewGroup
import momoi.mod.qqpro.util.Utils

fun fixViewPagerSync(root: View) {
    val cn = root.javaClass.name
    if (cn.endsWith("ViewPager2")) {
        try {
            val currentItem = root.javaClass.getMethod("getCurrentItem").invoke(root) as? Int ?: return
            val rvField = root.javaClass.getDeclaredField("k")
            rvField.isAccessible = true
            val rv = rvField.get(root) as? View ?: return
            val lm = rv.javaClass.getMethod("getLayoutManager").invoke(rv) ?: return
            val actualPage = lm.javaClass.getMethod("findFirstCompletelyVisibleItemPosition").invoke(lm) as? Int ?: return
            Utils.log("VP sync: currentItem=$currentItem actualPage=$actualPage")
            if (actualPage >= 0 && actualPage != currentItem) {
                Utils.log("VP sync: FIXING $currentItem → $actualPage")
                root.javaClass.getMethod("setCurrentItem", Int::class.java).invoke(root, actualPage)
            }
        } catch (e: Exception) {
            Utils.log("VP sync: err ${e.javaClass.simpleName}: ${e.message}")
        }
    }
    if (root is ViewGroup) {
        for (i in 0 until root.childCount) fixViewPagerSync(root.getChildAt(i))
    }
}
