package momoi.plugin.apkmixin

import com.android.tools.smali.dexlib2.ValueType
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.DexFile
import com.android.tools.smali.dexlib2.iface.value.TypeEncodedValue
import com.android.tools.smali.dexlib2.immutable.ImmutableDexFile
import com.android.tools.smali.dexlib2.rewriter.DexFileRewriter
import com.android.tools.smali.dexlib2.rewriter.DexRewriter
import com.android.tools.smali.dexlib2.rewriter.Rewriter
import com.android.tools.smali.dexlib2.rewriter.RewriterModule
import com.android.tools.smali.dexlib2.rewriter.Rewriters
import com.google.common.base.Stopwatch
import lanchon.multidexlib2.BasicDexFileNamer
import lanchon.multidexlib2.DexIO
import lanchon.multidexlib2.MultiDexIO
import momoi.plugin.apkmixin.utils.Smali
import momoi.plugin.apkmixin.utils.SmaliMethod
import momoi.plugin.apkmixin.utils.ZipUtil
import momoi.plugin.apkmixin.utils.child
import momoi.plugin.apkmixin.utils.info
import momoi.plugin.apkmixin.utils.findClass
import momoi.plugin.apkmixin.utils.getDexCount
import momoi.plugin.apkmixin.utils.lifecycle
import momoi.plugin.apkmixin.utils.toClassDef
import momoi.plugin.apkmixin.utils.toSmali
import org.gradle.api.Project
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile
import kotlin.getValue

class MixinProcessor(
    private val project: Project,
    private val inputDexDir: File = project.layout.buildDirectory.dir("intermediates/dex/release/mergeDexRelease").get().asFile,
    private val targetApkFile: File = project.projectDir.child("mixin").child(extension.targetApk.orEmpty()),
    private val extension: ApkMixinExtension = project.extensions.getByType(ApkMixinExtension::class.java)
) {

    fun processMixin() {
        val (srcDex, trgDex, targetZipFile) = loadDexFiles()
        val mixinClasses = findMixinClasses(srcDex, trgDex)
        lifecycle("Processing Mixin...")
        val stopwatch = Stopwatch.createStarted()
        val (newClassesDex, modifiedClasses) = processClasses(srcDex, trgDex, mixinClasses)
        lifecycle("Mixin processed in ${stopwatch.elapsed(TimeUnit.MILLISECONDS)}ms")
        writeDexOutput(trgDex, newClassesDex, modifiedClasses, targetZipFile)
    }

    private fun loadDexFiles(): Triple<DexFile, DexFile, ZipFile> {
        val srcDex = MultiDexIO.readDexFile(
            /* multiDex = */ true,
            /* file = */ inputDexDir,
            /* namer = */ BasicDexFileNamer(),
            /* opcodes = */ null,
            /* logger = */ null
        )

        val trgDex = MultiDexIO.readDexFile(
            /* multiDex = */ true,
            /* file = */ targetApkFile,
            /* namer = */ BasicDexFileNamer(),
            /* opcodes = */ null,
            /* logger = */ null
        )
        val targetZipFile = ZipFile(targetApkFile)

        return Triple(srcDex, trgDex, targetZipFile)
    }

    private fun findMixinClasses(srcDex: DexFile, targetDex: DexFile): Map<ClassDef, ClassDef> {
        val mixinClasses = mutableMapOf<ClassDef, ClassDef>()
        var mixinClassCount = 0
        srcDex.classes.forEach { srcDef ->
            if (srcDef.annotations.any { it.type == "Lmomoi/anno/mixin/Mixin;" }) {
                mixinClasses[srcDef] = srcDef.superclass?.let { s -> targetDex.findClass(s) }
                    ?: throw FileNotFoundException(
                        "Can not find mixin ${srcDef.type} target class ${srcDef.superclass}"
                    )
                info("Found Mixin Class: ${srcDef.type} to ${srcDef.superclass}")
                mixinClassCount++
            } else {
                srcDef.methods.forEach { m ->
                    m.annotations.forEach { a ->
                        if (a.type == "Lmomoi/anno/mixin/StaticHook;") {
                            val ref = (a.elements.first().value as TypeEncodedValue).value
                            mixinClasses[srcDef] = targetDex.findClass(ref)!!
                            mixinClassCount++
                        }
                    }
                }
            }
        }
        lifecycle("Found $mixinClassCount mixin classes")
        return mixinClasses
    }

    private fun processClasses(
        srcDex: DexFile,
        targetDex: DexFile,
        mixinClasses: Map<ClassDef, ClassDef>
    ): Pair<MutableDexFile, Map<String, ClassDef>> {
        val newClassesDex = MutableDexFile()
        val changedDef = mutableMapOf<ClassDef, ClassDef>()
        val modifiedClasses = mutableMapOf<String, ClassDef>()

        srcDex.classes.forEach { srcDef ->
            var content = srcDef.toSmali()
            mixinClasses.forEach { (rs, rt) ->
                content = content.replace(rs.type, rt.type)
            }

            when {
                !mixinClasses.containsKey(srcDef) -> {
                    val stripped = srcDef.type in extension.stripClasses ||
                        extension.stripPrefixes.any { p -> srcDef.type.startsWith(p) }
                    // Add the class if the target lacks it, or if it replaces a shell that is
                    // being stripped from the target dex (dedup check must run after stripping).
                    if (targetDex.findClass(srcDef.type) == null || stripped) {
                        newClassesDex.classes.add(content.toClassDef()!!)
                    }
                }
                else -> mixinToTargetClass(srcDef, content, mixinClasses, changedDef, modifiedClasses)
            }
        }

        return newClassesDex to modifiedClasses
    }

    private fun mixinToTargetClass(
        srcDef: ClassDef,
        content: String,
        mixinClasses: Map<ClassDef, ClassDef>,
        lastClassesMap: MutableMap<ClassDef, ClassDef>,
        modifiedClasses: MutableMap<String, ClassDef>
    ) {
        val rawTrgDef = mixinClasses[srcDef]!!
        val trgDef = lastClassesMap[rawTrgDef] ?: rawTrgDef
        val srcSmali = Smali(content)
        val trgSmali = Smali(trgDef.toSmali())

        processMethods(srcSmali, trgSmali)
        processFields(srcSmali, trgSmali)

        val newTrgDef = trgSmali.toText().toClassDef()!!
        lastClassesMap[trgDef] = newTrgDef
        modifiedClasses[newTrgDef.type] = newTrgDef
    }

    private fun processMethods(srcSmali: Smali, trgSmali: Smali) {
        srcSmali.methods.forEach { srcMethod ->
            // A @ConstructorHook method is not copied as a normal method; its body is
            // spliced into the target's matching <init> (see injectConstructorHook).
            if (srcMethod.body.contains("Lmomoi/anno/mixin/ConstructorHook;")) {
                injectConstructorHook(srcMethod, trgSmali)
                return@forEach
            }
            processMethodStaticHook(srcMethod)
            trgSmali.findMethod(srcMethod)?.let { trgMethod ->
                processMethodBody(srcMethod, trgMethod, trgSmali)
            }
            trgSmali.methods.add(srcMethod)
        }
    }

    /**
     * Splice a @ConstructorHook method's instructions onto the end of the target
     * constructor with the same argument signature. Constructors aren't parsed as
     * methods (the method regex excludes `<init>`), so the target `<init>` lives in
     * [Smali.otherLines] and is edited there as text. The hook's parameter registers
     * map 1:1 to the constructor's, so only parameter registers (p0..pN) may be used —
     * we reject any local (vN) to avoid clobbering the constructor's register file.
     */
    private fun injectConstructorHook(srcMethod: SmaliMethod, trgSmali: Smali) {
        val instructions = extractInjectableInstructions(srcMethod.body)
        if (instructions.isBlank()) return
        if (Regex("\\bv\\d+\\b").containsMatchIn(instructions)) {
            throw IllegalStateException(
                "@ConstructorHook ${srcMethod.name}(${srcMethod.args}) must use only parameter " +
                    "registers (p0..), no locals (vN). Body:\n$instructions"
            )
        }
        val args = Regex.escape(srcMethod.args)
        val ctorRegex = Regex(
            "(\\.method [^\\n]*constructor <init>\\($args\\)V.*?)(\\n[ \\t]*return-void)([ \\t]*\\n\\.end method)",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )
        val match = ctorRegex.find(trgSmali.otherLines)
            ?: throw IllegalStateException(
                "@ConstructorHook: target constructor <init>(${srcMethod.args})V not found"
            )
        val patched = match.groupValues[1] + "\n" + instructions + match.groupValues[2] + match.groupValues[3]
        trgSmali.otherLines = trgSmali.otherLines.replace(match.value, patched)
    }

    /**
     * Pull the executable instruction lines out of a method body: drop annotation
     * blocks, the .locals/.registers/.param/.prologue/.line/.local directives, the
     * closing .end method, and the trailing return. What remains is the code to splice.
     */
    private fun extractInjectableInstructions(body: String): String {
        val withoutAnnotations = body.replace(
            Regex("\\n[ \\t]*\\.annotation .*?\\.end annotation", setOf(RegexOption.DOT_MATCHES_ALL)),
            ""
        )
        return withoutAnnotations.lines()
            .map { it.trimEnd() }
            .filter { line ->
                val t = line.trim()
                t.isNotEmpty() &&
                    !t.startsWith(".locals") && !t.startsWith(".registers") &&
                    !t.startsWith(".param") && !t.startsWith(".prologue") &&
                    !t.startsWith(".line") && !t.startsWith(".local ") &&
                    !t.startsWith(".end") && t != "return-void" && t != "return"
            }
            .joinToString("\n") { if (it.startsWith(" ") || it.startsWith("\t")) it else "    $it" }
    }

    private fun processMethodStaticHook(srcMethod: SmaliMethod) {
        if (srcMethod.body.contains("Lmomoi/anno/mixin/StaticHook;")) {
            srcMethod.modifiers.add("static")
        }
    }

    private fun processMethodBody(srcMethod: SmaliMethod, trgMethod: SmaliMethod, trgSmali: Smali) {
        if (srcMethod.body.contains("Lmomoi/anno/mixin/PrivateCall;")) {
            srcMethod.modifiers.remove("public")
            srcMethod.modifiers.remove("protected")
            srcMethod.modifiers.add("private")
        }
        srcMethod.body = srcMethod.body.replace("invoke-super", "invoke-virtual")
        val rawCall = trgSmali.getMethodCall(trgMethod)
        var index = 0
        do {
            trgMethod.name += "_${index++}"
        } while (trgSmali.methods.filter { it.name == trgMethod.name }.size > 1)
        srcMethod.body = srcMethod.body.replace(rawCall, trgSmali.getMethodCall(trgMethod))
    }

    private fun processFields(srcSmali: Smali, trgSmali: Smali) {
        srcSmali.fields.forEach { srcField ->
            if (!trgSmali.fields.any { it.name == srcField.name }) {
                trgSmali.fields.add(srcField)
            }
        }
    }

    private fun writeDexOutput(trgDex: DexFile, newDex: DexFile, modifiedClasses: Map<String, ClassDef>, targetZipFile: ZipFile) {
        val rewriter = createDexRewriter(newDex, modifiedClasses)
        lifecycle("Writing dex...")
        val stopWatchWriteDex = Stopwatch.createStarted()
        val namer = BasicDexFileNamer()
        val outputDexDir = project.layout.buildDirectory.dir("mixinDex").get().asFile.also {
            if (!it.isDirectory && ((it.exists() && !it.delete()) || !it.mkdirs())) {
                throw IOException("Failed to create ${it.absolutePath}")
            }
        }
        
        MultiDexIO.writeDexFile(
            /* multiDex = */ true,
            /* threadCount = */ if (extension.useProcessorCountAsThreadCount) Runtime.getRuntime().availableProcessors() else targetZipFile.getDexCount(namer),
            /* file = */ outputDexDir,
            /* namer = */ namer,
            /* dexFile = */ rewriter.dexFileRewriter.rewrite(trgDex),
            /* maxDexPoolSize = */ DexIO.DEFAULT_MAX_DEX_POOL_SIZE,
            /* logger = */ null
        )

        stopWatchWriteDex.stop()
        lifecycle("Dex written in ${stopWatchWriteDex.elapsed(TimeUnit.MILLISECONDS)}ms")

        lifecycle("Zip to mixin apk...")
        val stopWatchZipToApk = Stopwatch.createStarted()
        val mixinApkFile = project.outputDir(extension).child(extension.output.mixinApkFileName)
        if (mixinApkFile.length() != targetApkFile.length()) {
            targetApkFile.copyTo(mixinApkFile, overwrite = true)
        }
        val filesToAdd = buildMap {
            putAll(outputDexDir.listFiles()?.associateBy { it.name } ?: emptyMap())
            putAll(collectInjectFiles())
            patchManifest(mixinApkFile)?.let { put("AndroidManifest.xml", it) }
        }
        ZipUtil.addOrReplaceFilesInZip(mixinApkFile, filesToAdd)

        stopWatchZipToApk.stop()
        lifecycle("Mixin apk written in ${stopWatchZipToApk.elapsed(TimeUnit.MILLISECONDS)}ms")
    }

    /**
     * Merge the user-authored manifest patch (`mixin/<manifestMerge>`) into [apk]'s binary manifest.
     * Returns the patched manifest file to inject into the APK, or null if there's no patch file or
     * nothing to merge (the build then proceeds with the manifest unchanged).
     */
    private fun patchManifest(apk: File): File? {
        val patchXml = targetApkFile.parentFile.child(extension.manifestMerge)
        if (!patchXml.isFile) return null
        val out = project.outputDir(extension).child("merged-AndroidManifest.xml")
        return runCatching {
            if (momoi.plugin.apkmixin.utils.ManifestMerger.merge(apk, patchXml, out)) {
                lifecycle("Merged manifest from ${patchXml.absolutePath}")
                out
            } else {
                lifecycle("Manifest merge skipped (nothing to merge)")
                null
            }
        }.getOrElse {
            lifecycle("Manifest merge failed: ${it.message}")
            null
        }
    }

    /**
     * Collects arbitrary files to add/replace in the output APK from the inject directory
     * (`mixin/<injectDir>`, default `mixin/inject`). The path of each file relative to that
     * directory becomes its zip entry name, so the tree mirrors the APK root. This is generic:
     * drop any `assets/...`, `res/...`, `lib/...`, etc. file there to bundle or override it.
     */
    private fun collectInjectFiles(): Map<String, File> {
        val injectRoot = targetApkFile.parentFile.child(extension.injectDir)
        if (!injectRoot.isDirectory) return emptyMap()
        val result = injectRoot.walkTopDown()
            .filter { it.isFile }
            .associate { file ->
                injectRoot.toPath().relativize(file.toPath()).toString().replace(File.separatorChar, '/') to file
            }
        if (result.isNotEmpty()) {
            lifecycle("Injecting ${result.size} file(s) from ${injectRoot.absolutePath}: ${result.keys.sorted()}")
        }
        return result
    }

    private fun createDexRewriter(newClassesDex: DexFile, modifiedClasses: Map<String, ClassDef>): DexRewriter {
        return DexRewriter(object : RewriterModule() {
            override fun getDexFileRewriter(rewriters: Rewriters): Rewriter<DexFile?> {
                return object : DexFileRewriter(rewriters) {
                    override fun rewrite(value: DexFile): DexFile {
                        return super.rewrite(
                            ImmutableDexFile(
                                value.opcodes,
                                buildList {
                                    addAll(
                                        value.classes.filterNot {
                                            it.type in extension.stripClasses ||
                                                extension.stripPrefixes.any { p -> it.type.startsWith(p) }
                                        }
                                    )
                                    addAll(newClassesDex.classes)

                                    val types = modifiedClasses.keys
                                    removeAll { it.type in types }
                                    addAll(modifiedClasses.values)
                                }
                            )
                        )
                    }
                }
            }
        })
    }
}
