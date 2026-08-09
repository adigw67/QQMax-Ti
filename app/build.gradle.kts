import java.text.SimpleDateFormat
import java.util.Date
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.AnnotationVisitor
import org.objectweb.asm.FieldVisitor
import org.objectweb.asm.MethodVisitor
import org.objectweb.asm.Opcodes

buildscript {
    dependencies {
        // Used by the patchStubJar task below to rewrite the compile-only stub jar.
        classpath("org.ow2.asm:asm:9.9")
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("momoi.plugin.apkmixin") apply true
}

// The target APK bundles androidx / android / kotlin classes that duplicate the build's own
// classpath (compileOnly androidx artifacts, AGP's android.jar and kotlin-stdlib). Keeping them
// in the stub jar shadows the real ones and breaks compilation (e.g. ktx extensions resolve to
// R8-inlined empty shells, RecyclerView types get mixed identities). Mirror the original
// projects' stub jars: drop those prefixes entirely. kotlinx/coroutines is dropped as well
// because the APK's copy is R8-stripped (no Metadata → extensions unresolvable); the real
// coroutines come from the kotlinx-coroutines-android dependency.
val droppedStubPrefixes = listOf("androidx/", "android/", "kotlin/", "kotlinx/coroutines/")

// Concrete subclass methods whose erased parameter types clash with the generic methods they
// override when Kotlin resolves inherited platform declarations (R8 left only the erased
// variants in the APK). These are compile-only stubs, so dropping them is safe.
val droppedStubMethods = mapOf(
    "com/tencent/qqnt/watch/chat/list/WatchRecentItemBuilder" to setOf(
        "a (Landroid/view/ViewGroup;ILcom/tencent/qqnt/chats/core/itempart/ItemPartCollect;Lcom/tencent/mobileqq/quibadge/IQUIBadgeDrag\$OnChangeModeListener;Lcom/tencent/qqnt/chats/core/adapter/OnRecentContactItemListener;)Lcom/tencent/qqnt/chats/core/adapter/holder/BaseChatViewHolder;",
        "c (Lcom/tencent/qqnt/chats/core/adapter/itemdata/RecentContactChatItem;Lcom/tencent/qqnt/chats/core/adapter/holder/BaseChatViewHolder;)V",
        "d (Lcom/tencent/qqnt/chats/core/adapter/itemdata/RecentContactChatItem;Lcom/tencent/qqnt/chats/core/adapter/holder/BaseChatViewHolder;)V",
        "e (Lcom/tencent/qqnt/chats/core/adapter/itemdata/RecentContactChatItem;Lcom/tencent/qqnt/chats/core/adapter/holder/BaseChatViewHolder;)V",
        "f (Lcom/tencent/qqnt/chats/core/adapter/itemdata/RecentContactChatItem;Lcom/tencent/qqnt/chats/core/adapter/holder/BaseChatViewHolder;Landroid/view/View\$OnClickListener;)V",
        "h (Lcom/tencent/qqnt/chats/core/adapter/itemdata/RecentContactChatItem;Lcom/tencent/qqnt/chats/core/adapter/holder/BaseChatViewHolder;)V",
        "i (Lcom/tencent/qqnt/chats/core/adapter/itemdata/RecentContactChatItem;Lcom/tencent/qqnt/chats/core/adapter/holder/BaseChatViewHolder;)V",
        "j (Lcom/tencent/qqnt/chats/core/adapter/itemdata/RecentContactChatItem;Lcom/tencent/qqnt/chats/core/adapter/holder/BaseChatViewHolder;)V",
        "l (Lcom/tencent/qqnt/chats/core/adapter/itemdata/RecentContactChatItem;Lcom/tencent/qqnt/chats/core/adapter/holder/BaseChatViewHolder;)V"
    ),
    "com/tencent/aio/part/root/panel/content/firstLevel/msglist/mvx/vb/core/AbsMsgListVB" to setOf(
        "K (Lcom/tencent/mvi/base/mvi/MviUIState;)V"
    ),
    "com/tencent/richframework/widget/matrix/RFWMatrixImageView" to setOf(
        "setOnLongClickListener (Landroid/view/View\$OnLongClickListener;)V"
    )
)

// Some stub classes in libs/source.jar are constructor-local anonymous classes carrying BOTH an
// InnerClasses (member) attribute and an EnclosingMethod (local) attribute — a combination D8/R8 rejects
// ("a member class cannot also be a non-member local class"), which blocks @Mixin-subclassing them (e.g.
// hooking QQNTC2CWatchActivity$mServiceConnection$1.onServiceConnected). The stub is compile-only and
// never ships in the APK, so we compile against a patched copy with EnclosingMethod stripped — pure member
// classes that dex cleanly, with no runtime effect (real classes come from the patched target APK).
val patchedStubJar = layout.buildDirectory.file("patched-libs/source.jar")
val patchStubJar = tasks.register("patchStubJar") {
    val srcJar = file("libs/source.jar")
    inputs.file(srcJar)
    outputs.file(patchedStubJar)
    doLast {
        val out = patchedStubJar.get().asFile
        out.parentFile.mkdirs()
        ZipFile(srcJar).use { zip ->
            ZipOutputStream(out.outputStream().buffered()).use { zos ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val e = entries.nextElement()
                    val bytes = zip.getInputStream(e).readBytes()
                    val outBytes = if (e.name.endsWith(".class")) {
                        if (droppedStubPrefixes.any { e.name.startsWith(it) }) continue
                        val cr = ClassReader(bytes)
                        val cw = ClassWriter(cr, 0)
                        cr.accept(object : ClassVisitor(Opcodes.ASM9, cw) {
                            private var currentClassName: String? = null

                            override fun visit(
                                version: Int, access: Int, name: String?, signature: String?,
                                superName: String?, interfaces: Array<out String>?
                            ) {
                                currentClassName = name
                                super.visit(version, access and Opcodes.ACC_FINAL.inv(), name, signature, superName, interfaces)
                            }

                            // Drop the EnclosingMethod attribute (keep InnerClasses) → pure member class.
                            override fun visitOuterClass(owner: String?, name: String?, descriptor: String?) {}

                            override fun visitField(
                                access: Int, name: String?, descriptor: String?,
                                signature: String?, value: Any?
                            ): FieldVisitor {
                                return super.visitField(access and Opcodes.ACC_FINAL.inv(), name, descriptor, signature, value)
                            }

                            override fun visitMethod(
                                access: Int, name: String?, descriptor: String?,
                                signature: String?, exceptions: Array<out String>?
                            ): MethodVisitor? {
                                // Skip compiler-generated bridge methods: their erased signatures
                                // clash with the generic methods they bridge when Kotlin resolves
                                // inherited platform declarations (e.g. BaseRecentItemBuilder.f).
                                if (access and Opcodes.ACC_BRIDGE != 0) return null
                                val key = "$name $descriptor"
                                if (currentClassName?.let { droppedStubMethods[it]?.contains(key) } == true) return null
                                // De-privatize constructors too: some target classes have
                                // private/package-private <init> in the APK (e.g. BeaconPubParams,
                                // AutoSizeConfig) but the original projects' stubs exposed them.
                                // Hooks are only compiled against the stub; at runtime ApkMixin
                                // merges methods into the target class and never calls them.
                                val access2 = if (name == "<init>") {
                                    (access or Opcodes.ACC_PUBLIC) and (Opcodes.ACC_PRIVATE or Opcodes.ACC_PROTECTED).inv()
                                } else access
                                return super.visitMethod(access2 and Opcodes.ACC_FINAL.inv(), name, descriptor, signature, exceptions)
                            }

                            // Drop Kotlin annotations (Metadata, kotlin.jvm.*, ...) from stub
                            // classes: kotlinc otherwise reads the original Kotlin declaration
                            // (final / internal / visibility) from @Metadata and rejects hooks
                            // that subclass or override these classes.
                            override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
                                return if (descriptor.startsWith("Lkotlin/")) null
                                else super.visitAnnotation(descriptor, visible)
                            }

                            // dex2jar emits anonymous classes with an InnerClasses entry that has
                            // no inner_name. After EnclosingMethod is dropped above that leaves a
                            // nameless anonymous class the compiler rejects. Give such entries a
                            // member-class inner name (mirrors the original projects' stubs).
                            override fun visitInnerClass(name: String?, outerName: String?, innerName: String?, access: Int) {
                                val fixedInnerName = innerName ?: name?.substringAfterLast('$')
                                super.visitInnerClass(name, outerName, fixedInnerName, access)
                            }
                        }, 0)
                        cw.toByteArray()
                    } else {
                        bytes
                    }
                    zos.putNextEntry(ZipEntry(e.name))
                    zos.write(outBytes)
                    zos.closeEntry()
                }
            }
        }
    }
}

android {
    namespace = "momoi.mod.qqpro"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tencent.qqlite"
        minSdk = 19
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Baked-in build timestamp (local time of the machine that built the APK), shown in the
        // About page and crash/hang reports. Recomputed every build (input changes → not cached).
        val buildTime = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
        buildConfigField("String", "BUILD_TIME", "\"$buildTime\"")
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(project(":ApkMixin-annotation"))
    compileOnly(libs.androidx.appcompat)
    // Compile against the EnclosingMethod-stripped stub jar (see patchStubJar above) instead of the raw
    // libs/source.jar, so constructor-local anonymous stub classes can be @Mixin-subclassed.
    compileOnly(files(patchStubJar))
    compileOnly(libs.androidx.fragment)
    compileOnly(libs.androidx.constraintlayout)
    compileOnly(libs.androidx.recyclerview)
    compileOnly(libs.androidx.viewpager2)
    compileOnly(libs.androidx.core)
    compileOnly(libs.androidx.navigation.fragment)
    // The target APK's bundled core-ktx classes are R8-inlined empty shells (e.g.
    // androidx.core.view.ViewKt has no methods), so the ktx extension functions the hooks
    // use must be packaged into the mixin dex. androidx.core itself comes from the base APK.
    implementation("androidx.core:core-ktx:1.2.0") {
        exclude(group = "androidx.core", module = "core")
        exclude(group = "androidx.annotation", module = "annotation")
    }
    // kotlinx.coroutines APIs used by hooks (SupervisorJob, Dispatchers.Main, launch, ...).
    // The target APK bundles an old coroutines-core without the Android Main dispatcher.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.6.4")
}

apkMixin {
    versionName = "M2.5-4.4"
    targetApk = "source.apk"
    useProcessorCountAsThreadCount = project.properties["useProcessorCountAsThreadCount"] == "true"
    // Remove the empty core-ktx shells from the target APK's dex so the full classes
    // packaged above are the ones that load at runtime (no shadowing by empty shells).
    stripClasses = listOf(
        "Landroidx/core/graphics/PathKt;",
        "Landroidx/core/graphics/RectKt;",
        "Landroidx/core/net/UriKt;",
        "Landroidx/core/os/BundleKt;",
        "Landroidx/core/os/HandlerKt;",
        "Landroidx/core/os/TraceKt;",
        "Landroidx/core/text/HtmlKt;",
        "Landroidx/core/text/LocaleKt;",
        "Landroidx/core/text/StringKt;",
        "Landroidx/core/util/HalfKt;",
        "Landroidx/core/util/LruCacheKt;",
        "Landroidx/core/util/PairKt;",
        "Landroidx/core/util/RangeKt;",
        "Landroidx/core/util/SizeKt;",
        "Landroidx/core/view/MenuKt;",
        "Landroidx/core/view/ViewKt;",
        "Landroidx/core/view/ViewGroupKt;"
    )
    // The APK bundles an R8-stripped kotlinx-coroutines (no callers, no Main dispatcher).
    // Remove it entirely so the full 1.6.4 copy packaged above is what loads at runtime.
    stripPrefixes = listOf("Lkotlinx/coroutines/")

    signing {
        enabled = false
        keyFile = file("mixin/testkey.pk8")
        certFile = file("mixin/testkey.x509.pem")
    }

    output {
        signedFileName = "QQMax_${versionName}.apk"
    }
}
