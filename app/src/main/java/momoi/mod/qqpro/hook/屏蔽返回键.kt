package momoi.mod.qqpro.hook

import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.FragmentActivity
import com.tencent.qqnt.watch.mainframe.MainActivity
import momoi.anno.mixin.Mixin
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.util.Utils

@Mixin
class 屏蔽返回键 : MainActivity() {
    override fun onResume() {
        super.onResume()
        // WatchActivity.onResume re-enables the in-app right-swipe-back (fling) gesture every
        // time the activity resumes; turn it back off when the user disabled it.
        if (Settings.disableSwipeBack.value) {
            try {
                enableFlingGesture(false)
            } catch (e: Throwable) {
                Utils.log("disableSwipeBack: err ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java", replaceWith = ReplaceWith("放过我吧waring大爹"))
    override fun onBackPressed() {
        if (Settings.blockBack.value) {
            return
        }
        Utils.log("backToFirstPage: onBackPressed enabled=${Settings.backToFirstPage.value}")
        if (Settings.backToFirstPage.value) {
            try {
                val vp = findViewPager2(window.decorView)
                Utils.log("backToFirstPage: vp=${vp?.javaClass?.name}")
                if (vp != null) {
                    val current = vp.javaClass.getMethod("getCurrentItem").invoke(vp) as? Int
                    Utils.log("backToFirstPage: currentItem=$current")
                    if (current != null && current != 0) {
                        try {
                            vp.javaClass
                                .getMethod("setCurrentItem", Int::class.java, Boolean::class.java)
                                .invoke(vp, 0, true)
                        } catch (e: NoSuchMethodException) {
                            // (int, boolean) overload stripped by R8; single-arg smooth-scrolls
                            vp.javaClass
                                .getMethod("setCurrentItem", Int::class.java)
                                .invoke(vp, 0)
                        }
                        return
                    }
                }
            } catch (e: Exception) {
                Utils.log("backToFirstPage: err ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        super.onBackPressed()
    }

    private fun findViewPager2(root: View): View? {
        if (root.javaClass.name.endsWith("ViewPager2")) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                findViewPager2(root.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
}
