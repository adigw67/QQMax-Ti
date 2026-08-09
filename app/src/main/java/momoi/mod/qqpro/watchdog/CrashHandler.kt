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

        // The viewer runs in another process; give AMS a moment to spawn it before we go away.
        try {
            Thread.sleep(400)
        } catch (_: InterruptedException) {
        }

        try {
            defaultHandler?.uncaughtException(t, e)
        } finally {
            android.os.Process.killProcess(android.os.Process.myPid())
            exitProcess(10)
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
}
