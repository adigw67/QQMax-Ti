package momoi.mod.qqpro.watchdog

import android.content.Context
import android.os.Build
import android.util.DisplayMetrics
import momoi.mod.qqpro.util.Utils
import java.io.File

/**
 * Collects a block of device/app/runtime info to embed in crash & hang reports, so a report pulled
 * from the watch carries enough context (model, ROM, memory pressure, app version, uptime) to
 * diagnose without a live device. Every field is wrapped defensively — gathering diagnostics must
 * never itself throw inside a crash handler.
 */
object DeviceInfo {
    fun collect(ctx: Context): String = buildString {
        val app = ctx.applicationContext
        append("== 设备信息 ==\n")

        // App
        runCatching {
            val pm = app.packageManager
            val pi = pm.getPackageInfo(app.packageName, 0)
            append("应用包名: ${app.packageName}\n")
            append("应用版本: ${pi.versionName} (${versionCodeOf(pi)})\n")
        }.onFailure { append("应用版本: 获取失败 ($it)\n") }
        append("构建时间: ${momoi.mod.qqpro.BuildConfig.BUILD_TIME}\n")

        // Device / ROM
        append("设备: ${Build.MANUFACTURER} ${Build.BRAND} ${Build.MODEL}\n")
        append("产品: ${Build.PRODUCT} / ${Build.DEVICE} / ${Build.BOARD}\n")
        append("硬件: ${Build.HARDWARE}\n")
        append("ABI: ${Build.SUPPORTED_ABIS.joinToString(", ")}\n")
        append("Android: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\n")
        append("ROM 指纹: ${Build.FINGERPRINT}\n")
        append("ROM 版本: ${Build.DISPLAY} / ${Build.ID}\n")

        // Screen
        runCatching {
            val dm: DisplayMetrics = app.resources.displayMetrics
            append("屏幕: ${dm.widthPixels}x${dm.heightPixels} @${dm.densityDpi}dpi (x${dm.density})\n")
        }.onFailure { append("屏幕: 获取失败\n") }

        // Memory
        runCatching {
            val rt = Runtime.getRuntime()
            val used = (rt.totalMemory() - rt.freeMemory()) / MB
            val total = rt.totalMemory() / MB
            val max = rt.maxMemory() / MB
            append("JVM 内存: 已用 ${used}MB / 已分配 ${total}MB / 上限 ${max}MB\n")
        }.onFailure { append("JVM 内存: 获取失败\n") }

        runCatching {
            val info = android.app.ActivityManager.MemoryInfo()
            val am = app.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.getMemoryInfo(info)
            append("系统内存: 可用 ${info.availMem / MB}MB / 总 ${info.totalMem / MB}MB")
            append(if (info.lowMemory) " (低内存!)\n" else "\n")
        }.onFailure { append("系统内存: 获取失败\n") }

        // Storage (app cache/files volume)
        runCatching {
            val dir: File = app.filesDir
            append("存储: 可用 ${dir.usableSpace / MB}MB / 总 ${dir.totalSpace / MB}MB\n")
        }.onFailure { append("存储: 获取失败\n") }

        // Runtime
        runCatching {
            val upMs = android.os.SystemClock.uptimeMillis()
            append("系统运行: ${formatDuration(upMs)}\n")
        }.onFailure { }
        runCatching {
            append("进程: pid=${android.os.Process.myPid()} 进程名=${currentProcessName(app)}\n")
        }.onFailure { }
        append("CPU 核心: ${Runtime.getRuntime().availableProcessors()}\n")
        append("电量: ${batteryInfo(app)}\n")
    }

    private fun versionCodeOf(pi: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode
        else @Suppress("DEPRECATION") pi.versionCode.toLong()

    private fun currentProcessName(ctx: Context): String = runCatching {
        if (Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName()
        else {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            am.runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName ?: "?"
        }
    }.getOrDefault("?")

    private fun batteryInfo(ctx: Context): String = runCatching {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as android.os.BatteryManager
        val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
        "$pct%"
    }.getOrDefault("未知")

    private fun formatDuration(ms: Long): String {
        var s = ms / 1000
        val h = s / 3600; s %= 3600
        val m = s / 60; s %= 60
        return "${h}h ${m}m ${s}s"
    }

    private const val MB = 1024L * 1024L
}
