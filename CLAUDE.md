# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Project Is

QQPro is a QQ smartwatch app mod (for NWear-QQ). It patches the original QQ APK at build time using a custom Gradle plugin called **ApkMixin**, which replaces methods in the target APK's smali bytecode with hook implementations written in Kotlin.

## Build Commands

```bash
# Build release APK (output: app/dist/QQPro_<version>.apk)
./gradlew MixinApk-release

# Build debug APK
./gradlew MixinApk-debug

# Speed up build using all CPU cores
./gradlew MixinApk-release -PuseProcessorCountAsThreadCount=true
```

There are no tests. The build process compiles the Kotlin hooks, decompiles the target APK (`app/mixin/source.apk`) to smali, patches it, and re-signs with `app/mixin/testkey.pk8`.

## ApkMixin Hook System

Hooks are written as normal Kotlin classes that extend the QQ target class and are annotated with `@Mixin`:

```kotlin
@Mixin
class MyHook : TargetClass() {
    override fun targetMethod() {
        // replaces the original method body
        super.targetMethod() // calls original
    }
}
```

Key rules:
- **No constructor hooks** — never add fields with initial values in a `@Mixin` class
- Non-`override` methods/fields are copied into the patched class and are accessible via casting: `(targetObj as MyHook).myMethod()`
- Static method hooks use `object` + `@StaticHook` + `@JvmStatic`, and the method name must end with a single underscore (`_`) to avoid compile-time collision

The annotation library is in `ApkMixin-annotation/`; the Gradle plugin that does the actual bytecode patching is in `ApkMixin/` (uses smali/dexlib2).

## Project Structure

- `app/src/main/java/momoi/mod/qqpro/` — all hook code
  - `hook/` — feature hooks, organized by area:
    - `aio_cell/` — chat message cell rendering (reply, forward, card, markdown views)
    - `action/` — stateful singletons tracking current contact, message list, self info
    - `style/` — UI style tweaks (scale, emoji size, input bar, long-press menu)
    - `view/` — custom view helpers (dialog, network image, smooth scroll)
  - `lib/` — DSL for building Android views programmatically (no XML layouts used)
  - `api/` — HTTP and group API helpers
  - `util/` — `Utils` (app context, logging), `Json`, `Linkify`, `ThreadManager`
  - `enums/` — QQ NT message type and element type constants
  - `msg/` — message element models (e.g. `PicElement`)
  - `Settings.kt` — `SharedPreferences`-backed settings with `FloatPref`/`BooleanPref` helpers
  - `QQNT.kt` — thin wrapper around QQ's `KernelServiceUtil` for group member queries
  - `MsgUtil.kt` — message summarization and dialog helpers
  - `Extensions.kt` — `ViewGroup`, `String`, and reflection utilities
- `app/src/main/java/com/tencent/` and `android/` — stub Java files for QQ/AOSP classes that exist in `app/libs/source.jar` (compile-only)
- `app/mixin/source.apk` — the target QQ APK that gets patched (not committed as source)

## Naming Conventions

- **New class/file names must use English** — do not create new files or classes with Chinese names. Existing Chinese-named files (`版权信息.kt`, `设置页.kt`, etc.) are kept as-is but should not be used as a template for new code.

## Key Conventions

- **Views are built in Kotlin DSL** (`lib/ViewDSL.kt`, `lib/LinearLayout.kt`, etc.) — no XML layouts exist in this project
- **Logging**: use `Utils.log("message")` (tag: `"QQ Max"`) as the primary logger — this matches what the decompiled app uses and shows up in the log viewer. `Log.e("QQPro", ...)` is secondary. **Never use `adb logcat -c` to clear logs.**
- Settings are persisted in SharedPreferences named `"qqpro"`; the existing WearQQ app uses `"wearqq"`
- `CurrentContact`, `CurrentMsgList`, `SelfContact` in `hook/action/` are global singletons updated by hooks — query them to know the active chat context
- Colors for nick tags and UI elements are centralized in `Colors.kt`

## Decompiled App Sources

The target APK has been fully decompiled to two locations — use these to understand QQ internals before writing hooks:

- `app/decompiled/jadx/` — Java source decompiled by jadx (best for reading logic). **Note:** jadx renames fields to avoid package name collisions (e.g. field `a` → `f12345a`, Kotlin property `vp` may stay as `vp` or get renamed). Always verify real bytecode field names in the smali.
- `app/decompiled/apktool/` — smali bytecode + resources from apktool. Use smali to find the **actual** field/method names used at runtime (e.g. `grep "\.field" WatchAIOFragment.smali`).

Both are in `.gitignore`. To regenerate:
```bash
jadx -d app/decompiled/jadx --no-res --show-bad-code app/mixin/source.apk
apktool d -f -o app/decompiled/apktool app/mixin/source.apk
```
