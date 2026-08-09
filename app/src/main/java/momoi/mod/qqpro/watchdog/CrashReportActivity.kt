package momoi.mod.qqpro.watchdog

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import momoi.mod.qqpro.lib.SwipeBackLayout
import momoi.mod.qqpro.lib.material.M3

/**
 * Standalone crash/hang report viewer. Declared with `android:process=":crash"` so it lives in a
 * separate process and renders even after the main (crashing) process is gone — see [Watchdog].
 *
 * This is NOT an ApkMixin hook, so it may freely use lambdas / anonymous classes. It also avoids
 * the project's `lib` dp helpers (which read `Utils.application`) since that singleton may be
 * uninitialised in the `:crash` process — it converts dp from the local display metrics instead.
 */
class CrashReportActivity : Activity() {

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun sp(view: TextView, value: Float) =
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, value)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Block screenshots/screen-recording of this screen so people send the actual log text
        // (复制 / 导出) instead of an unreadable, truncated screenshot of the report.
        window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )

        val kind = intent?.getStringExtra(Watchdog.EXTRA_KIND) ?: Watchdog.KIND_CRASH
        val report = runCatching { Watchdog.reportFile(this).readText() }
            .getOrElse { "(无法读取报告: $it)" }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(M3.surface)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }

        root.addView(TextView(this).apply {
            text = if (kind == Watchdog.KIND_HANG) "应用卡死了" else "应用崩溃了"
            setTextColor(M3.onSurface)
            sp(this, 15f)
            gravity = Gravity.CENTER
        })
        root.addView(TextView(this).apply {
            text = "请把下面的报告发给开发者"
            setTextColor(M3.onSurfaceVariant)
            sp(this, 10f)
            gravity = Gravity.CENTER
            setPadding(0, dp(4), 0, dp(8))
        })

        // Report body fills the remaining space and scrolls.
        val body = TextView(this).apply {
            text = report
            setTextColor(M3.onSurface)
            sp(this, 9f)
            setTextIsSelectable(true)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setBackgroundColor(0xFF_0A0A0A.toInt())
        }
        root.addView(
            ScrollView(this).apply { addView(body) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        // First row: 复制 / 分享 / 重启
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        fun cell(v: View, left: Int, right: Int) = row.addView(
            v,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = left
                rightMargin = right
            }
        )
        cell(button("复制", M3.primary) { copyReport(report) }, 0, dp(3))
        cell(button("分享", 0xFF_4CAF50.toInt()) { shareReport(report) }, dp(3), dp(3))
        cell(button("保存", 0xFF_009688.toInt()) { saveReport(report) }, dp(3), 0)
        root.addView(
            row,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // Second row: 重启 / 关闭
        val row2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
        }
        fun cell2(v: View, left: Int, right: Int) = row2.addView(
            v,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                leftMargin = left
                rightMargin = right
            }
        )
        cell2(button("重启", 0xFF_FF9800.toInt()) { restartApp() }, 0, dp(3))
        cell2(button("关闭", M3.surfaceContainer) { finish() }, dp(3), 0)
        root.addView(
            row2,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        )

        // SwipeBackLayout gives watches without a hardware back button a swipe-to-dismiss gesture.
        setContentView(
            SwipeBackLayout(this).apply {
                addView(
                    root,
                    ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                )
                onSwipeBack = { finish() }
            }
        )

        // Consume the report so it doesn't resurface on the next normal launch.
        runCatching { Watchdog.reportFile(this).delete() }
    }

    private fun button(label: String, color: Int, onClick: () -> Unit): TextView =
        TextView(this).apply {
            text = label
            setTextColor(M3.onColor(color)) // contrast against the button's own fill color
            sp(this, 12f)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(9), dp(8), dp(9))
            background = GradientDrawable().apply {
                setColor(color)
                cornerRadius = dp(18).toFloat()
            }
            setOnClickListener { onClick() }
        }

    private fun copyReport(report: String) {
        runCatching {
            val cm = getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cm?.setPrimaryClip(ClipData.newPlainText("crash report", report))
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
        }.onFailure { Log.e("Watchdog", "copy failed", it) }
    }

    private fun saveReport(report: String) {
        runCatching {
            val saved = LogExporter.save(this, "qqpro_crash", report)
            if (saved == null) {
                Toast.makeText(this, "保存失败", Toast.LENGTH_LONG).show()
            } else {
                // Long toast + the saved path so it's findable on a watch (no file browser).
                val msg = if (saved.inDownloads) "已保存到下载文件夹:\n${saved.location}"
                else "无法写入下载，已保存到:\n${saved.location}"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }.onFailure { Log.e("Watchdog", "save failed", it) }
    }

    private fun shareReport(report: String) {
        runCatching {
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, report)
            }
            startActivity(
                Intent.createChooser(send, "分享报告").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }.onFailure { Log.e("Watchdog", "share failed", it) }
    }

    private fun restartApp() {
        runCatching {
            packageManager.getLaunchIntentForPackage(packageName)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                startActivity(it)
            }
        }.onFailure { Log.e("Watchdog", "restart failed", it) }
        finish()
    }
}
