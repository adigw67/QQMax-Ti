# API 19 兼容性修复清单

测试设备为 **小米米兔手表 4（Mi Kids Smartwatch 4），Android 4.4.4 / API 19**。该 ROM 缺少大量
API 21+ / 23+ / 26+ 的类与方法，本项目的 hook 代码只要触达这些 API 就会抛
`NoClassDefFoundError` / `NoSuchMethodError`（异常栈会被 watchdog 捕获，见文末排查方法）。

## 1. 已修复项（当前 v7 全部包含）

| API | 设备上状态 | 修复方式 | 涉及文件 |
| --- | --- | --- | --- |
| `RippleDrawable` | 类不存在 | `M3.ripple()` 返回 `Drawable`：API 21+ 用 RippleDrawable，API 19 用 StateListDrawable 按压态；所有裸 `RippleDrawable(...)` 改走 `M3.ripple` | `lib/material/Material.kt`、`hook/MenuPanelLayout.kt`、`hook/ChatMultiSelect.kt`、`hook/style/长按菜单调整.kt` |
| `View.setClipToOutline` | 方法不存在 | 新增 `setClipToOutlineCompat()`，API < 21 时 no-op，替换全库 19 处调用 | `lib/View.kt` + 15 个使用文件 |
| `PathInterpolator` | 类不存在 | `M3Motion` 6 条缓动曲线走 `bezier()` 助手：API 21+ 用 PathInterpolator，API 19 用 `DecelerateInterpolator(1.8f)`；`MainNav.EMPHASIZED` 改用 `M3Motion.EasingEmphasized` | `lib/material/M3Motion.kt`、`hook/MainNav.kt` |
| `ViewOutlineProvider` / `Outline` | 类不存在 | 全部 8 处 `outlineProvider = ...` 加 `Build.VERSION.SDK_INT >= 21` 守卫 | `qzone/*`（FeedM3/MediaPicker/FriendPicker）、`QZoneTopBar.kt`、`view/InlineVideoView.kt`、`view/MemberPickerFragment.kt`、`call/MaterialCallUi.kt` |
| `Context.getSystemService(Class)` | 方法不存在（API 23+） | 改用字符串形式 `getSystemService("input_method")` 等 | `lib/material/M3QQEditText.kt` 等 8 处 |
| `finishAndRemoveTask` | 方法不存在（API 21+） | 改用 `finish()` | watchdog 相关 Activity |
| `Context.startForegroundService` | 方法不存在（API 26+） | API < 26 用 `startService()` | `hook/call/ScreenShare.kt` |
| `NotificationChannel` 系列 | 类不存在（API 26+） | `CallNotification`/`ResidentNotification` 已有 SDK 守卫；`NotificationAlert` 在 `runCatching` 内兜底 | `hook/call/CallNotification.kt`、`hook/ResidentNotification.kt`、`hook/NotificationAlert.kt` |
| `android.graphics.Bitmap.Config.RGBA_F16/HARDWARE` | 不存在 | 由 dexopt 日志确认，代码未引用 | — |

## 2. 多 dex 专项（防止原生崩溃与启动死锁）

原版 QQ 自带 **BoostMultiDex**（native 提取次 dex）。本项目另用 **JavaMultiDex**（纯 Java 安装次
dex）。两套同时运行会在每个进程里重复加载同一批 dex，且在多个进程（主进程/MSF/boost 提取进程）
并发读写同一缓存文件，导致：

- `com.tencent.qqlite:MSF` 的 `FinalizerDaemon` 线程在 `libdvm.so` 的 `dvmDexFileFree` 处 **SIGBUS**；
- 启动期 ANR / 死锁。

修复（v5 起生效）：

1. **`BoostMultiDex.install` 打成空壳**（7 指令直接返回）：`OptimizeService` 不再被启动，
   不再产生 `boost_multidex/` 缓存与 `:boost_multidex` 进程；
2. **`JavaMultiDex` 使用按进程隔离目录** `files/jm_<进程名>` / `jm_<进程名>_opt`：
   不同进程不再并发写同一文件（旧实现共享 `files/javamultidex/`）。

两处补丁在构建后以 `SwapClass` 从已验证旧包换入（见 `BUILD.md` 第 4 节）。

## 3. 排查方法

1. 开启日志：设置 → 启用日志（或写 `shared_prefs/qqpro.xml` 加 `<boolean name="enableLog" value="true"/>`）；
2. 复现问题；
3. 拉日志：

```bash
adb pull /sdcard/Android/data/com.tencent.qqlite/cache/qqpro_debug.log .
```

4. 崩溃时 watchdog 会弹报告页（独立 `:crash` 进程），报告同时写入 `files/qqpro_crash_report.txt`；
5. 原生崩溃看 tombstone：

```bash
adb shell "su -c 'ls -la /data/tombstones/'"
adb shell "su -c 'cat /data/tombstones/tombstone_XX'"
```

## 4. 通用规则

新增代码前先问一句：**这个类/方法 API 19 有吗？** 常见高危名单：

`RippleDrawable`、`PathInterpolator`、`ViewOutlineProvider`、`Outline`、`setClipToOutline`、
`setElevation`、`setZ`、`setBackgroundTintList`、`setForeground`（API 23+）、
`getSystemService(Class)`（API 23+）、`finishAndRemoveTask`、`NotificationChannel`、
`startForegroundService`、`WindowInsets` 系列、`setStatusBarColor`。
