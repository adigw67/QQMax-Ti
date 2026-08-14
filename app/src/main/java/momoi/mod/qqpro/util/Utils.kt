package momoi.mod.qqpro.util

import android.annotation.SuppressLint
import android.app.Application
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import android.widget.Toast
import com.tencent.mobileqq.widget.QQToast
import com.tencent.qphone.base.util.QLog
import com.tencent.mobileqq.utils.TimeFormatterUtils
import androidx.core.net.toUri
import momoi.mod.qqpro.safeCacheDir

object Utils {
    @SuppressLint("PrivateApi")
    val application = Class.forName("android.app.ActivityThread").getMethod("currentApplication")
        .invoke(null) as Application
    val isDebug =
        try {
            val info = application.applicationInfo
            (info.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (e: Exception) {
            false
        }

    /**
     * Show QQ's native toast (QQToast) instead of Android's [Toast], whose layout
     * breaks under this watch ROM's ultra-large DPI.
     */
    fun toast(context: Context, text: CharSequence, longDuration: Boolean = false) {
        val duration = if (longDuration) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        QQToast.i(context, text, duration).l()
    }

    /** Copy [text] to the system clipboard and show a native QQ toast (no Android toast). */
    fun copyToClipboard(context: Context, text: CharSequence, toastText: CharSequence = "已复制") {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("label", text))
        toast(context, toastText)
    }

    fun formatTime(timestamp: Long): CharSequence =
        TimeFormatterUtils.a(application, 3, timestamp, true, true)!!

    private var debugWatcher: Any? = null
    fun debugger(catch: Any?) {
        debugWatcher = catch
        Log.e("QQQQQQQQQQ", "debugger!")
    }

    /** The on-device debug log file ([log] appends here). Exposed so the debug menu can read it. */
    val debugLogFile by lazy {
        // 固定用内部缓存目录：外部存储路径在这块表上可能不存在/只读，导致日志永远落不了盘。
        val base = runCatching { application.cacheDir }.getOrNull() ?: application.filesDir
        java.io.File(base, "qqpro_debug.log")
    }

    // Read the "启用日志" toggle straight from the qqpro prefs (not the Settings object) so logging
    // has no dependency on Settings init. SharedPreferences caches in memory, so this is cheap.
    private val proPrefs by lazy { application.getSharedPreferences("qqpro", Context.MODE_PRIVATE) }

    /**
     * Logging requires the explicit "启用日志" setting (default off). Debug builds previously forced
     * it on, which made every hot-path log do a synchronous append to qqpro_debug.log on the main
     * thread, and every Log.e/QLog.e write blocked whenever the device's logcat ring buffer was full
     * (this watch's 256K buffers stay ~100% full, so a main-thread write can block forever → 输入
     * 超时 ANR/卡死). Both are now offloaded to a single daemon thread with a bounded queue:
     * [log] never blocks the caller, never grows unbounded, and preserves ordering. The setting
     * still exists for diagnostics; it just can't stall the UI anymore.
     */
    val loggingEnabled: Boolean get() = proPrefs.getBoolean("enableLog", false)

    // 单线程日志泵：offer 永不阻塞调用线程（队列满时丢弃最旧语义由 FIFO 自然保证——超限直接丢弃
    // 新日志，诊断日志本来就是尽力而为）；写 logcat 与同步追加文件都发生在这个后台线程上。
    private val logQueue = java.util.concurrent.LinkedBlockingQueue<String>(1000)

    private val logPump: Thread by lazy {
        Thread({
            while (true) {
                val msg = logQueue.take()
                // 文件优先：logcat 缓冲写满时会阻塞写者，而日志文件是诊断的可靠通道，必须先落盘。
                try {
                    debugLogFile.parentFile?.mkdirs()
                    debugLogFile.appendText("${System.currentTimeMillis()} $msg\n")
                } catch (t: Throwable) {
                }
                try {
                    Log.e("QQ Max", msg)
                } catch (t: Throwable) {
                }
                try {
                    QLog.e("QQ Max", 1, msg)
                } catch (t: Throwable) {
                }
            }
        }, "qqpro-log").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
            start() // 必须启动，否则队列永不消费、日志被静默丢弃
        }
    }

    /** Save the full on-device debug log file to the Downloads folder. Returns the saved location. */
    fun saveLogToDownloads(): momoi.mod.qqpro.watchdog.LogExporter.Saved? =
        momoi.mod.qqpro.watchdog.LogExporter.saveFile(application, "qqpro_debug", debugLogFile, "log")

    fun log(msg: String) {
        if (!loggingEnabled) return
        try {
            logPump // 确保泵线程已启动（lazy，只启动一次）
            logQueue.offer(msg) // 非阻塞；队列满时丢弃本条，绝不卡主线程
        } catch (t: Throwable) {
        }
    }

    val heightPixels = Resources.getSystem().displayMetrics.heightPixels
    val widthPixels = Resources.getSystem().displayMetrics.widthPixels

    /**
     * 圆屏（或圆角方形）手表检测。
     *  - API 23+：直接用系统 [android.content.res.Configuration.isScreenRound]；
     *  - API 19（本表）：系统无该字段，用「形态启发式」——圆表/方表的显示矩阵是正方形或
     *    近正方形（圆脸内切于其中，且方形屏同样存在四角被圆边/表圈裁切的问题），因此
     *    [isSquareScreen] 视为圆屏。
     *
     * 注意：刻意**不含** isDebug——debug 包也可能跑在手机上，若把 isDebug 当圆屏会让手机调试时
     * 被误套圆表遮罩。方形/近方形启发式已覆盖绝大多数圆表（含带下巴的圆表，其显示矩阵仍为方形）。
     */
    // 计算属性（get()）而非 val：isSquareScreen 声明在本属性之后，用 get() 保证访问时对象已
    // 初始化完毕，且 isScreenRound 每次都读系统实时配置。
    val isRoundScreen: Boolean get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Resources.getSystem().configuration.isScreenRound
    } else {
        isSquareScreen
    }

    /** 正方形 / 近正方形显示矩阵（圆形与方形手表）。 */
    val isSquareScreen: Boolean = widthPixels > 0 && heightPixels > 0 &&
        Math.abs(heightPixels - widthPixels) <= Math.max(heightPixels, widthPixels) / 8

    fun openUrl(url: String) {
        val normalized = if (url.contains("://")) url else "https://$url"
        val intent = Intent(Intent.ACTION_VIEW, normalized.toUri())
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(application.packageManager) != null) {
            application.startActivity(intent)
        }
    }

    /** Open the system dialer prefilled with [number] (does not place the call automatically). */
    fun dialNumber(number: String) {
        val intent = Intent(Intent.ACTION_DIAL, "tel:$number".toUri())
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (intent.resolveActivity(application.packageManager) != null) {
            application.startActivity(intent)
        } else {
            toast(application, "无法拨号")
        }
    }
}
