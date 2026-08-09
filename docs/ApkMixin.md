# ApkMixin —— 使用指南

ApkMixin 是 QQ Max 用来给原版 QQ 打补丁的自研 Gradle 插件。它把用 Kotlin 写的 Hook 类编译后，在构建时直接替换 / 注入到目标 APK 的 smali 字节码里——**永不直接修改 `source.apk`**。

- 注解库：`ApkMixin-annotation/`（`momoi.anno.mixin.*`）
- 插件实现：`ApkMixin/`（基于 smali / dexlib2 / multidexlib2；核心在 `MixinProcessor`）

本文档汇总当前版本（≥ M2.0）的全部能力。基础约定也见根目录 `CLAUDE.md`。

---

## 1. 基础：`@Mixin` 替换实例方法

Hook 写成继承目标 QQ 类、并加 `@Mixin` 注解的普通 Kotlin 类：

```kotlin
@Mixin
class MyHook : TargetClass() {
    override fun targetMethod() {
        // 替换原方法体
        super.targetMethod()   // 调用原方法
    }
}
```

匹配方式：ApkMixin 按**方法签名（方法名 + 参数）**匹配，**不依赖** Kotlin 的 `override` 关键字。处理器把目标原方法改名保留（`name += "_0"`，供 `super` 调用），再把 `@Mixin` 方法以原名合并进去。

要点：

- **一个源 `.kt` 文件只能对应一个目标类**。多个目标会互相覆盖（后者生效），其余静默无效。详见第 6 节。
- **不支持构造函数 Hook 改写整个 `<init>`** —— 不要给 `@Mixin` 类的字段加初始值（用 `@ConstructorHook` 往构造函数里塞代码，见第 5 节）。
- 非 `override` 的方法 / 字段会被**复制**进补丁后的目标类，可在别处把目标对象**强转**为 Hook 类来调用：`(targetObj as MyHook).myMethod()`。
- 建议 Hook 类里只放必要代码。

---

## 2. `@StaticHook` 替换静态方法

```kotlin
@Mixin
object ExampleStatic : TargetClass() {
    @StaticHook
    @JvmStatic
    fun targetMethod_(...) { ... }
}
```

- 定义为 `object`；方法加 `@JvmStatic`；方法名后加**一个下划线**（避免与原方法重复声明导致编译失败）。
- 顶层 `@StaticHook fun name(...)`（不用 `object` / `@JvmStatic` / 下划线）**也可行**（按 名 + 参 + 返回值 匹配，如 `FixSavePicCrash.kt`），但顶层函数的"源类"是文件的合成 `*Kt` 类——所以**同一文件里所有顶层 `@StaticHook` 必须指向同一个目标类**（见第 6 节）。

---

## 3. `@PrivateCall` 替换私有实例方法

替换目标类的 **private** 方法：

```kotlin
@Mixin
class MyHook : TargetClass() {
    // 注意：不能 override 父类的 private 方法（它不可见），
    // Kotlin 会把它当作同签名的新方法编译
    @PrivateCall          // momoi.anno.mixin.PrivateCall
    fun startForegroundCompat() { ... }
}
```

- 声明时**不写 `override`**。
- 加 `@PrivateCall`：让合并后的方法在 smali 里保持 **private/direct**，这样目标里原有的 `invoke-direct ...->method()` 调用仍能正确解析。**不加** `@PrivateCall` 则方法变成 public/virtual，原 `invoke-direct` 会校验失败。

> 已用于：把原生 `MsfService.startForegroundCompat()`（private）替换为标题化常驻通知（`ResidentNotification.kt`）。

四种 Hook 一览：

| 注解 | 作用 |
| --- | --- |
| 普通 `override` | 替换普通虚方法 |
| `@StaticHook` | 替换静态方法 |
| `@PrivateCall` | 替换 private 实例方法 |
| `@ConstructorHook` | 往 `<init>` 里拼接指令（见下） |

---

## 4. 资源 / 资产注入

有两套**互不相同**的注入机制（编辑任一注入输入后，增量构建可能不重跑——用 `--rerun-tasks` 强制；见第 6 节）。

### 4.1 `mixin/inject/` —— 逐字节注入（`injectDir`）

`app/mixin/inject/` 下的任意文件树，按"相对该目录的路径 = APK 内 entry 名"原样加入 / 替换进输出 APK。

- 例：`inject/assets/x.zip` → APK `assets/x.zip`；`inject/res/...` → `res/...`
- 只能加新文件或**替换已存在条目的字节**，不能新建资源 id、也不能把位图改成 XML drawable。
- 实现：`MixinProcessor.collectInjectFiles()` + `ZipUtil.addOrReplaceFilesInZip`。
- 用例：内置经典 / 大表情（`inject/assets/bigface.zip` + `bigface_index.json`）。**任何资产打包都复用它，别硬编码逐文件逻辑。**

### 4.2 `mixin/inject-res/` —— 注册到 `resources.arsc`（`injectResDir`）

`app/mixin/inject-res/` 下的标准 `res/` 树会被编码进 `resources.arsc`：**新增**文件资源（分配新 id）或按 `type/name`**替换 / 扩展**已存在资源（含新增配置限定符如 `-anydpi-v26`）。

- 仅支持**文件类资源**（drawable / mipmap / xml / png…）；**不支持** `values/` 的 colors / strings——需要时引用框架资源如 `@android:color/white`。
- 实现：`utils/ResourceInjector.kt`（ARSCLib `io.github.reandroid:ARSCLib`），在 `MixinPlugin` 的 `processManifest` 与 `sign` 之间、对 `unsigned.apk` 运行。
- 用例（自适应启动图标）：图标是 `@drawable/icon`。`inject-res/drawable-anydpi-v26/icon.xml`（`<adaptive-icon>`，bg `@android:color/white`、fg `@drawable/qqpro_ic_fg`）+ `inject-res/drawable-nodpi/qqpro_ic_fg.png`。命名为 `icon` 即给现有条目加 v26 变体——API ≥ 26 按系统形状裁切，旧系统保留原位图。**换图标**：替换 `qqpro_ic_fg.png`（logo 放内部 ~72% 安全区、四周透明）后重建。

---

## 5. `@ConstructorHook` —— 往构造函数拼指令

把代码拼接到目标构造函数的 `return-void` 之前，用来把 ctor 参数存进新增字段，避免"替换整个构造函数"带来的 super 调用 / 校验器问题。

```kotlin
@Mixin
class BaseUserActionInfoHook : BaseUserActionInfo {
    @JvmField var profileUid: String? = null

    @ConstructorHook
    fun captureUid(uid: String?, nick: String?, uin: String?) {
        profileUid = uid           // 把 ctor 丢弃的 uid 重新捕获到字段
    }
}
```

要点（`MixinProcessor.injectConstructorHook`）：

- 构造函数不被当作普通方法解析（正则排除 `<init>`），位于 `Smali.otherLines`；Hook 方法的指令按参数 1:1 映射拼接到 ctor 末尾。
- **方法体只能用参数寄存器（p0..），不能用局部变量（vN）**——这是被强制的。**用可空参数**避免 Kotlin 的非空检查 intrinsics 产生 vN。
- 验证：对产物 `apktool d -r`，检查目标 smali 有新字段 + `return-void` 前的 `iput-object`。

> 已用于：让灰条（撤回 / 邀请 / 改群名等）里的成员名可点击——`BaseUserActionInfo` 的 ctor 接收 `(uid,nick,uin)` 却一个都不存，用 `@ConstructorHook` 补存 uid（一处覆盖所有灰条类型）。

---

## 6. 清单合并

ApkMixin 内置二进制清单合并器：编写纯文本 `app/mixin/AndroidManifest.xml`，构建时合并进目标 APK 的已编译清单——用来加 intent-filter / 组件 / 权限，而**不**改 `source.apk`。

- 新属性覆盖旧值；新子节点追加（按 元素名 + `android:name` 匹配；`manifest` / `application` 仅按名匹配；追加按结构去重 → 幂等）。
- 实现：`utils/ManifestMerger.kt`（基于 `pxb.android.axml` 树模型 + `public.xml` 属性→资源 id 表，二者都打包在 `ApkMixin/libs/ManifestEditor-2.0.jar`），在 `MixinProcessor.patchManifest()` 接线。可配 `extension.manifestMerge`（默认 `AndroidManifest.xml`，相对 `mixin/`）。
- 注意：ManifestEditor 自带 CLI 只能改 version / debug / label，**加不了** intent-filter / 嵌套节点，所以才需要这个树合并器；其后处理会保留已合并节点。
- 验证：`aapt dump xmltree <apk> AndroidManifest.xml | grep -n SEND`。

---

## 7. 编译 / 运行常见坑

写 Hook 时高频踩坑，按出现频率排列：

### 7.1 `@Mixin` 方法体里引用的新类必须是 public

`@Mixin` 方法体会被复制进**另一个包**的目标类（如 `moye.wearqq.SettingsActivity`）。若引用了顶层 `private` 类 / data class（顶层 `private` 编译成 package-private），运行时抛 `IllegalAccessError`（数组类型如 `Foo[]` 同样会中招）。

- **修复**：把这类辅助类声明为 `public`（普通 `class Foo`，不加 `private`）。
- 文件级 `private` 的 *var / fun* 没问题（走合成访问器）；只有被 mixin 体引用的 `private` *类 / 类型* 会坏。

### 7.2 不要在 `@Mixin` 方法体里写匿名 / 局部类

匿名类（如 `object : SeekBar.OnSeekBarChangeListener {}`）会生成在 Hook 的包下、构造函数 package-private，被复制到目标包的代码无法调用 → `IllegalAccessError`。

- **修复**：把多方法监听接口的实现挪到 `lib/` 里的**非 inline** 辅助函数（如 `Switch.kt` 的 `doAfterSwitch`、`SeekBar.kt` 的 `onProgressChanged`），只传函数类型 lambda。**必须非 inline**，否则又被内联回调用点。
- **Lambda 命名冲突变体**：当**两个** `@Mixin` 类 Hook **同一个**目标方法，各自体内的 inline lambda（如 `x.setOnClickListener { … }`）会被合成成同名（`方法名$lambda$0`）→ 互相串号崩溃。修复：把 `setOnClickListener { … }` 挪到 Hook 文件的**顶层函数**里，其 lambda 就编译进文件的 `*Kt` 类（我们的包），而非合并进目标。

### 7.3 override 的参数是 `FunctionN` 时用函数类型语法

目标方法参数是 `kotlin.jvm.functions.FunctionN`（如 Java stub 声明 `Function2<A, B, Unit>`）时，`override fun` 必须写成 `(A, B) -> Unit`，**不能**写 `Function2<A, B, Unit>`，否则报 *"overrides nothing"*（提示里打印的"潜在签名"看起来一模一样，差别不可见）。

### 7.4 Hook 被 R8 改名的抽象方法 override，需要委托 stub

`@Mixin` 继承的目标类 override 了某真实编译期依赖（androidx 等）的抽象方法，但该 override 被 R8 改了名时，Kotlin 编译器仍认为依赖里那个原名抽象方法没实现。

- 例：`FrameAdapter extends FragmentStateAdapter`，apk 里 override 叫 `f(int)`，但 androidx 声明的抽象方法是 `createFragment(int)`。
- **修复**：两个都 override —— `override fun f(pos)` 装真正的 Hook（运行时 apk 调的是改名后的 `f`）；再加委托 `override fun createFragment(pos) = f(pos)` 仅为过编译（运行时不被调用，无害）。
- 注意此招需要两个**不同**的名字；若两者都叫 `d`（平台声明冲突 + 相同 JVM 签名），则无解（见 `GrayTipMention` 里 `WatchGrayTipsCell` 的处理）。

---

## 8. 构建与验证

```bash
# 编辑过任意 inject 输入后，强制重跑注入（增量构建可能复用旧产物）
rm -f app/dist/unsigned.apk app/dist/QQMax_*.apk
./gradlew MixinApk-debug --rerun-tasks
```

验证补丁是否真的生效，用产物（不是源码）反编译核对：

- `apktool d -r -f -o /tmp/check app/dist/QQMax_*.apk`，再 grep 目标 `.smali`：原方法体已被替换、字段 / `@ConstructorHook` 指令到位、没有杂散的 Hook 方法落到错的类。
- 清单：`aapt dump xmltree <apk> AndroidManifest.xml`。
- 资源：`aapt2 dump resources <apk> | grep -A4 drawable/icon`（确认 `(anydpi-v26)` 配置）。

> 调试日志：手表 ROM 会吞 logcat / QLog，用 `Utils.log(...)`（写入 `/sdcard/Android/data/com.tencent.qqlite/cache/qqpro_debug.log`，`adb pull` 读取）。界面提示用 `Utils.toast(...)`（QQToast），**不要**用 `android.widget.Toast`（手表大 DPI 下布局会坏）。
</content>
