package momoi.mod.qqpro.watchdog

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import momoi.mod.qqpro.util.Utils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Detects main-thread stalls. A ticker reposts itself on the main looper every second; a daemon
 * thread checks how long ago the last tick ran. If the main thread hasn't ticked for
 * [THRESHOLD_MS], it's considered hung: a report (with a thread dump) is saved and the
 * (separate-process) [CrashReportActivity] is shown. Unlike a crash we do NOT kill the process —
 * a hang may be transient — and we re-arm once the main thread recovers, so a single stall
 * reports only once.
 */
class HangWatcher(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var lastTick = 0L

    @Volatile
    private var reported = false

    private val ticker = object : Runnable {
        override fun run() {
            lastTick = SystemClock.uptimeMillis()
            mainHandler.postDelayed(this, CHECK_INTERVAL_MS)
        }
    }

    fun start() {
        lastTick = SystemClock.uptimeMillis()
        mainHandler.post(ticker)
        Thread({ monitor() }, "QQPro-HangWatcher").apply {
            isDaemon = true
            start()
        }
        Utils.log("Watchdog: HangWatcher started (threshold ${THRESHOLD_MS}ms)")
    }

    private fun monitor() {
        while (true) {
            try {
                Thread.sleep(CHECK_INTERVAL_MS)
            } catch (_: InterruptedException) {
            }
            val stalled = SystemClock.uptimeMillis() - lastTick
            if (stalled > THRESHOLD_MS) {
                if (!reported) {
                    reported = true
                    onHang(stalled)
                }
            } else {
                // Main thread recovered → re-arm for the next hang.
                reported = false
            }
        }
    }

    private fun onHang(durationMs: Long) {
        Log.w("Watchdog", "main thread hang: ${durationMs}ms")
        Utils.log("Watchdog: main thread hang ${durationMs}ms")
        val report = buildString {
            append("应用卡死 $durationMs ms\n")
            append("时间: ${timestamp()}\n\n")
            append(DeviceInfo.collect(context))
            append("\n")
            append("主线程堆栈:\n")
            append(mainThreadStack())
            append("\n\n全部线程堆栈:\n")
            append(allThreadStacks())
        }
        Watchdog.report(context, Watchdog.KIND_HANG, report)
    }

    private fun mainThreadStack(): String =
        Looper.getMainLooper().thread.stackTrace.joinToString("\n") { "  at $it" }

    private fun allThreadStacks(): String = buildString {
        for ((thread, frames) in Thread.getAllStackTraces()) {
            append("Thread: ${thread.name}\n")
            for (f in frames) append("  at $f\n")
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())

    companion object {
        private const val CHECK_INTERVAL_MS = 1000L

        // 8s: high enough to avoid false positives during heavy work (image decode, cold start)
        // on a slow watch, while still catching real freezes.
        private const val THRESHOLD_MS = 8000L
    }
}
