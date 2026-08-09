package momoi.plugin.apkmixin.utils

import com.reandroid.apk.ApkModule
import com.reandroid.apk.xmlencoder.FilePathEncoder
import com.reandroid.arsc.chunk.PackageBlock
import java.io.File

/**
 * Generic FILE-resource injector. Encodes the file resources under a standard Android `res/`
 * directory into an existing (AndResGuard-obfuscated) APK via ARSCLib, registering them in
 * `resources.arsc`:
 *  - resources whose `type/name` already exists are REPLACED (new file overrides old, and a new
 *    config qualifier such as `-anydpi-v26` is added as an extra variant of the same entry),
 *  - resources that don't exist are ADDED (a new entry/id is allocated).
 *
 * This is what the verbatim file-inject tree (`mixin/inject/`) cannot do: that path only swaps the
 * bytes of an existing zip entry, so it can't add new resources, and can't turn a bitmap into an
 * XML drawable (Android picks bitmap-vs-XML by the resource file extension recorded in the table).
 *
 * Scope: FILE resources only (drawable/mipmap/layout/anim/xml/raw/… — anything that is one file per
 * resource). `values/` resources (color/string/dimen/…) are NOT handled here; reference framework
 * resources (e.g. `@android:color/white`) instead, or add them another way.
 *
 * Typical use — adaptive launcher icon: drop `res/drawable-anydpi-v26/icon.xml` (an `<adaptive-icon>`
 * referencing `@drawable/<fg>` + `@android:color/white`) and `res/drawable-nodpi/<fg>.png` into the
 * inject-res dir. Naming the XML `icon` adds a v26 variant to the existing `@drawable/icon`, so
 * launchers on API >= 26 mask it to the system shape while older ones keep the bitmap — no manifest
 * edit needed. In-tree `@type/name` refs resolve against the target package; `@android:...` refs and
 * namespaced attributes resolve against ARSCLib's bundled framework.
 */
object ResourceInjector {

    /** Encode every file resource under [resDir] into [apk] in place (added or replaced). */
    fun inject(apk: File, resDir: File) {
        if (!resDir.isDirectory) return
        if (resDir.walkTopDown().none { it.isFile }) return

        val apkModule = ApkModule.loadApkFile(apk)
        try {
            val pkg = apkModule.tableBlock?.pickOne()
                ?: throw IllegalStateException("No resource package found in ${apk.name}")

            // FilePathEncoder only encodes files for entries that already exist in the table (it does
            // not allocate ids), and it resolves @type/name refs inline — so every resource the tree
            // defines or references must exist first. Pre-create an entry for each file resource.
            preCreateEntries(pkg, resDir)

            FilePathEncoder(apkModule).encodePackageResDir(pkg, resDir)

            val tmp = File(apk.parentFile, apk.name + ".restmp")
            apkModule.writeApk(tmp)
            apkModule.close()
            if (!apk.delete()) throw IllegalStateException("Could not replace ${apk.name}")
            if (!tmp.renameTo(apk)) {
                tmp.copyTo(apk, overwrite = true)
                tmp.delete()
            }
            lifecycle("Injected resources from ${resDir.absolutePath} into ${apk.name}")
        } catch (e: Exception) {
            runCatching { apkModule.close() }
            throw e
        }
    }

    /** Create (idempotently) a resource entry per file: type/qualifiers from the dir, name from the file. */
    private fun preCreateEntries(pkg: PackageBlock, resDir: File) {
        resDir.listFiles()?.forEach { typeDir ->
            if (!typeDir.isDirectory) return@forEach
            val dash = typeDir.name.indexOf('-')
            val type = if (dash < 0) typeDir.name else typeDir.name.substring(0, dash)
            val qualifiers = if (dash < 0) "" else typeDir.name.substring(dash + 1)
            typeDir.listFiles()?.forEach { file ->
                if (!file.isFile) return@forEach
                val name = file.name.substringBefore('.')
                pkg.getOrCreate(qualifiers, type, name)
            }
        }
    }
}
