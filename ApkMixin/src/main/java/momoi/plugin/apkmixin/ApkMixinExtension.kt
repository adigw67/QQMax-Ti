package momoi.plugin.apkmixin

import groovy.lang.Closure
import momoi.plugin.apkmixin.utils.child
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.util.internal.ConfigureUtil
import java.io.File
import kotlin.math.sign

const val DEFAULT_MIXIN_APK_NAME = "mixin.apk"
const val DEFAULT_UNSIGNED_APK_NAME = "unsigned.apk"
const val DEFAULT_SIGNED_APK_NAME = "signed.apk"

open class ApkMixinExtension {
    var versionName = ""
    var targetApk: String? = null
    var output = OutputExtension()
    var signing = SigningExtension()
    var useProcessorCountAsThreadCount = false

    /**
     * Smali class descriptors (e.g. "Landroidx/core/view/ViewKt;") to remove from the target
     * APK's dex while patching. Used to drop R8-inlined empty ktx shells so the full
     * replacement classes packaged in the mixin dex are the ones that load at runtime.
     */
    var stripClasses: List<String> = emptyList()

    /**
     * Smali type prefixes (e.g. "Lkotlinx/coroutines/") whose matching classes are removed
     * from the target APK's dex while patching, so packaged replacement classes load.
     */
    var stripPrefixes: List<String> = emptyList()

    /**
     * Directory (relative to the `mixin/` project subfolder) whose file tree is injected into the
     * output APK verbatim. Each file's path RELATIVE to this directory becomes the zip entry name,
     * so the tree mirrors the APK root: e.g. `mixin/inject/assets/foo.zip` → apk `assets/foo.zip`,
     * `mixin/inject/res/drawable/x.png` → apk `res/drawable/x.png`. Existing entries are replaced.
     * Lets you bundle/override arbitrary assets or resources without touching the source APK on disk.
     */
    var injectDir: String = "inject"

    /**
     * Plain-text AndroidManifest XML (relative to the `mixin/` project subfolder) that is MERGED
     * into the target APK's binary manifest at build time — new attributes override old, new nodes
     * are added (matched by element name + `android:name`). Lets you declare extra intent-filters,
     * permissions, components, etc. without editing the source APK. If the file is absent, the
     * manifest is left untouched. See [momoi.plugin.apkmixin.utils.ManifestMerger].
     */
    var manifestMerge: String = "AndroidManifest.xml"

    /**
     * Directory (relative to the `mixin/` project subfolder) holding a standard Android `res/` tree
     * that is ENCODED into the output APK's `resources.arsc` via ARSCLib — unlike [injectDir] (which
     * only swaps bytes of existing zip entries), this can ADD new resources and REPLACE existing ones
     * by `type/name`, including turning a bitmap into an XML drawable. The directory must directly
     * contain the resource type folders (`drawable...`, `mipmap...`, `values...`). If absent or empty,
     * nothing happens. See [momoi.plugin.apkmixin.utils.ResourceInjector].
     */
    var injectResDir: String = "inject-res"

    fun output(action: Action<OutputExtension>) {
        action.execute(output)
    }

    fun signing(action: Action<SigningExtension>){
        action.execute(signing)
    }
}

open class SigningExtension {
    var enabled = true
    var keyFile: File? = null
    var certFile: File? = null
}

open class OutputExtension {
    var outputDir: String = "dist"
    var unsignedFileName: String = DEFAULT_UNSIGNED_APK_NAME
    var signedFileName: String = DEFAULT_SIGNED_APK_NAME
    var mixinApkFileName: String = DEFAULT_MIXIN_APK_NAME
}

internal fun Project.outputDir(extension: ApkMixinExtension) = projectDir.child(extension.output.outputDir)
