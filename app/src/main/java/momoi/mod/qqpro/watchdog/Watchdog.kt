package momoi.mod.qqpro.watchdog

import android.content.Context
import android.content.Intent
import momoi.mod.qqpro.Settings
import momoi.mod.qqpro.util.Utils
import java.io.File

/**
 * App watchdog: captures uncaught exceptions ([CrashHandler]) and main-thread hangs
 * ([HangWatcher]), persists a report, and shows it in [CrashReportActivity].
 *
 * The report viewer deliberately runs in its OWN process (`:crash`, declared in
 * `app/mixin/AndroidManifest.xml`). When the app crashes the main process is torn down and any
 * Activity/Fragment hosted in it is force-finished, so it cannot render the report. A separate
 * process survives the crashing process dying and can display the report immediately.
 */
object Watchdog {
    /** Extra carrying "crash" or "hang" so the viewer can word its title. */
    const val EXTRA_KIND = "qqpro_watchdog_kind"
    const val KIND_CRASH = "crash"
    const val KIND_HANG = "hang"

    private const val REPORT_FILE = "qqpro_crash_report.txt"

    @Volatile
    private var installed = false

    /** The file (in the app's private storage, shared across processes) holding the last report. */
    fun reportFile(ctx: Context): File = File(ctx.applicationContext.filesDir, REPORT_FILE)

    /** Install crash + hang capture. Call once, from the main process (the MainActivity hook). */
    fun install(ctx: Context) {
        if (installed) return
        installed = true
        val app = ctx.applicationContext
        Utils.log("Watchdog: installing crash capture")
        Thread.setDefaultUncaughtExceptionHandler(CrashHandler(app))
        // Hang detection is opt-out: it can false-positive when the watch suspends/dozes.
        if (Settings.watchdogEnabled.value) {
            HangWatcher(app).start()
        } else {
            Utils.log("Watchdog: hang watcher disabled by setting")
        }
    }

    /** Persist [report] and launch the report viewer in the `:crash` process. */
    fun report(ctx: Context, kind: String, report: String) {
        val app = ctx.applicationContext
        try {
            reportFile(app).writeText(report)
        } catch (e: Throwable) {
            Utils.log("Watchdog: failed to write report: $e")
        }
        try {
            val intent = Intent(app, CrashReportActivity::class.java)
                .addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TASK or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION
                )
                .putExtra(EXTRA_KIND, kind)
            app.startActivity(intent)
        } catch (e: Throwable) {
            Utils.log("Watchdog: failed to launch report activity: $e")
        }
    }
}
