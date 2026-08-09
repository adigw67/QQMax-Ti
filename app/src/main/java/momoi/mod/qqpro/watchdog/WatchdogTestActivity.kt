package momoi.mod.qqpro.watchdog

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import momoi.mod.qqpro.lib.SwipeBackLayout
import momoi.mod.qqpro.lib.material.M3

/**
 * Watchdog test panel: each button deliberately crashes or hangs the app so the separate-process
 * crash/hang viewer ([CrashReportActivity]) can be verified. Opened from the settings 调试 section.
 *
 * A standalone Activity (not an ApkMixin hook, not a Fragment) so it can be launched from the plain
 * settings Activity and runs in the main process — the crash must propagate to the app's
 * uncaught-exception handler for the viewer to fire.
 */
class WatchdogTestActivity : Activity() {

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun sp(view: TextView, value: Float) =
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, value)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
        }

        column.addView(TextView(this).apply {
            text = "Watchdog 测试"
            setTextColor(M3.onSurface)
            sp(this, 15f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(12))
        })

        section(column, "崩溃测试")
        button(column, "空指针 (NPE)") { val s: String? = null; s!!.length }
        button(column, "数组越界") { IntArray(1)[5].toString() }
        button(column, "除以零") {
            (1 / (System.currentTimeMillis() - System.currentTimeMillis()).toInt()).toString()
        }
        button(column, "抛出异常") { throw RuntimeException("测试崩溃") }
        button(column, "后台线程崩溃") { Thread { throw RuntimeException("后台线程测试崩溃") }.start() }

        section(column, "卡死测试")
        button(column, "卡死 10 秒") {
            val end = System.currentTimeMillis() + 10_000
            @Suppress("ControlFlowWithEmptyBody")
            while (System.currentTimeMillis() < end) {
            }
        }

        section(column, "操作")
        button(column, "关闭") { finish() }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(M3.surface)
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }

        setContentView(
            SwipeBackLayout(this).apply {
                setBackgroundColor(M3.surface)
                addView(
                    scroll,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                onSwipeBack = { finish() }
            }
        )
    }

    private fun section(parent: LinearLayout, title: String) {
        parent.addView(TextView(this).apply {
            text = title
            setTextColor(M3.primary)
            sp(this, 11f)
            setPadding(0, dp(12), 0, dp(6))
        })
    }

    private fun button(parent: LinearLayout, label: String, action: () -> Unit) {
        val btn = TextView(this).apply {
            text = label
            setTextColor(M3.onPrimary)
            sp(this, 13f)
            gravity = Gravity.CENTER
            setPadding(0, dp(9), 0, dp(9))
            background = GradientDrawable().apply {
                setColor(M3.primary)
                cornerRadius = dp(18).toFloat()
            }
            // Run after the click is handled so the trigger is realistic.
            setOnClickListener { Handler(Looper.getMainLooper()).post { action() } }
        }
        parent.addView(
            btn,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(6) }
        )
    }
}
