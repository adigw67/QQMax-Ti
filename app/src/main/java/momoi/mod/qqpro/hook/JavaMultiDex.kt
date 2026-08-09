package momoi.mod.qqpro.hook

import android.content.Context
import dalvik.system.DexClassLoader
import momoi.anno.mixin.Mixin
import java.io.File
import java.util.zip.ZipFile

/**
 * Android 4.4 has no native multidex support and this watch ROM's BoostMultiDex (native
 * libboost_multidex.so) is unreliable on low-RAM devices / unavailable on x86. Install the
 * secondary dex with the standard Java-only multidex technique BEFORE the original
 * attachBaseContext runs, so the app works on any arch without BoostMultiDex.
 */
@Mixin
class JavaMultiDex : com.tencent.qqnt.watch.app.WatchApplication() {
    override fun attachBaseContext(base: Context?) {
        if (base != null) {
            try {
                installSecondaryDex(base)
            } catch (t: Throwable) {
                // never block startup on multidex failure
            }
        }
        super.attachBaseContext(base)
    }

    private fun installSecondaryDex(ctx: Context) {
        val apkPath = ctx.applicationInfo.sourceDir
        val appLoader = ctx.classLoader
        val dexDir = File(ctx.filesDir, "javamultidex").apply { mkdirs() }
        val optimizedDir = File(ctx.filesDir, "javamultidex_opt").apply { mkdirs() }

        val zip = ZipFile(apkPath)
        try {
            var i = 2
            while (true) {
                val name = if (i == 1) "classes.dex" else "classes$i.dex"
                val entry = zip.getEntry(name) ?: break
                val out = File(dexDir, name)
                zip.getInputStream(entry).use { ins ->
                    out.outputStream().use { ous -> ins.copyTo(ous) }
                }
                try {
                    appendDex(appLoader, out.absolutePath, optimizedDir.absolutePath)
                } catch (t: Throwable) {
                    // skip a dex that fails to load
                }
                i++
            }
        } finally {
            zip.close()
        }
    }

    private fun appendDex(appLoader: ClassLoader, dexPath: String, optimizedDir: String) {
        val cl = DexClassLoader(dexPath, optimizedDir, null, appLoader)
        val clPathList = field(cl, "pathList")
        val clDexElements = field(clPathList, "dexElements") as Array<*>

        val appPathList = field(appLoader, "pathList")
        val appDexElementsField = appPathList.javaClass.getDeclaredField("dexElements")
        appDexElementsField.isAccessible = true
        val existing = appDexElementsField.get(appPathList) as Array<*>
        val merged = ArrayList<Any>(existing.size + clDexElements.size)
        existing.forEach { if (it != null) merged.add(it) }
        clDexElements.forEach { if (it != null) merged.add(it) }
        appDexElementsField.set(appPathList, merged.toTypedArray())
    }

    private fun field(obj: Any, name: String): Any {
        var c: Class<*>? = obj.javaClass
        while (c != null) {
            try {
                val f = c.getDeclaredField(name)
                f.isAccessible = true
                return f.get(obj)
            } catch (e: NoSuchFieldException) {
                c = c.superclass
            }
        }
        throw NoSuchFieldException(name)
    }
}
