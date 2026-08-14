package momoi.mod.qqpro.watchdog

import android.content.Context
import android.util.Log
import momoi.mod.qqpro.util.Utils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.system.exitProcess

/**
 * Catches uncaught exceptions on any thread, persists a report, launches the (separate-process)
 * [CrashReportActivity] to show it, then lets the process die.
 */
class CrashHandler(private val context: Context) : Thread.UncaughtExceptionHandler {
    private val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()

    override fun uncaughtException(t: Thread, e: Throwable) {
        Log.e("Watchdog", "uncaught exception on thread ${t.name}", e)
        val stack = Log.getStackTraceString(e)
        Utils.log("Watchdog: uncaught exception on ${t.name}: $stack")

        val report = buildString {
            append("应用崩溃\n")
            append("时间: ${timestamp()}\n")
            append("线程: ${t.name}\n\n")
            append(DeviceInfo.collect(context))
            append("\n")
            append(stack)
        }
        Watchdog.report(context, Watchdog.KIND_CRASH, report)

        // 本 ROM（Xposed 改造）下走 defaultHandler 的 VM 收尾会在 AndroidRuntime::start 的
        // DestroyJavaVM 等待里卡死——表现为“崩溃后进程假死 + ANR 弹窗”（实测 B 站小程序
        // 消息未捕获异常后 15 秒 ANR）。这里不调用 defaultHandler，日志/报告落盘后直接干净
        // 杀进程，让系统按正常崩溃重启，避免假死。
        try {
            android.os.Process.killProcess(android.os.Process.myPid())
        } finally {
            exitProcess(10)
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
}
