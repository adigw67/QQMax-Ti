# QQProMax（QQ Max）M2.5-4.4 发布包

> 面向儿童手表（Android 4.4.4 / API 19）的 QQ 增强改版，基于***QQMax***

## 这是什么

QQProMax 在原版手表 QQ（`com.tencent.qqlite`）的基础上，通过自研的 **ApkMixin** 工具把 Kotlin 编写的功能注入到原版字节码里：

- **不改原版 APK 的签名**（保留腾讯 META-INF），老设备可直接覆盖安装；
- 聊天、空间（说说）、联系人、资料卡等界面整体重做为 **Material 3 风格**；
- 补齐了大量手表端缺失或难用的功能（完整输入、表情、语音、大表情、群管理、说说等）；
- 所有 hook 逻辑均在本项目的 `app/src/main/java/momoi/` 与 `app/src/main/java/com/tencent/` 中实现，构建时再注入原版。

当前版本：**M2.5-4.4（v18 / API19fix）**，包名 `com.tencent.qqlite`，构建日期 2026-08-10。

**v17 新增**：设置 → 外观主题 →「联网字体包」——从官方源下载 MiSans（优先显示）+ GNU Unifont（生僻字兜底），仅本应用进程内生效，防生僻字/扩展区字形缺失。

**v18 修复**：字体覆盖判定改为构建期从官方 MiSans cmap 生成的精确 BMP 位图（8KB 内嵌、O(1) 查表），去掉运行时解析 8MB 字体文件——修复部分手表上「常见中文也被判为缺字、整屏退回 Unifont 点阵」的问题。

## 本目录内容

```
QQProMax-M2.5-4.4-发布包/
├── README.md                  ← 本文件（自述文档）
├── 安装包/
│   └── QQMax-Ti_M2.5-4.4-API19fix-v18.apk   ← 当前可安装包（5-dex，腾讯原签名保留）
├── 文档/
│   ├── ApkMixin.md            ← ApkMixin 注入机制原始文档
│   ├── HOOKS.md               ← hook 方法技术文档（@Mixin 机制 + 关键 hook 清单）
│   ├── BUILD.md               ← 构建与 5-dex 组装文档
│   ├── API19-COMPAT.md        ← API 19 兼容性修复清单与排查方法
│   └── KNOWN-BUGS.md          ← 当前已知 bug 清单（待修复）
└── 源码/                      ← 本项目全部自有代码（不含腾讯 QQ 原版代码）
```

## 版权与代码边界

本包**不包含腾讯 QQ 原版代码**：

- `app/libs/source.jar`（QQ 类桩）、`app/mixin/source.apk`（原版 QQ 安装包）、`ApkMixin-gen-dep/raw.jar`（构建期依赖）均**未**随包分发；
- `app/src/main/java/com/tencent/` 下的文件是本项目**自行编写**的 hook 桩/替换类（用于在编译期"借用" QQ 类名），不是腾讯原码；
- 构建时需要自行准备原版 QQ 手表 APK 并放到 `app/mixin/source.apk`（见 `文档/BUILD.md`）。

## 安装

1. 把 `安装包/QQMax-Ti_M2.5-4.4-API19fix-v18.apk` 传到手表（`adb install -r` 或直接拷贝到手表存储点击安装）；
2. 首次启动会做 5 个 dex 的提取与优化（老手表约 2~5 分钟，属正常）；
3. 进入「QQ Max」欢迎页 → 扫码登录。

## 已知问题

当前有 11 个待修复 bug（详见 `文档/KNOWN-BUGS.md`），修复后将更新安装包并重新打包。

## 构建

见 `文档/BUILD.md`。构建命令：

```bash
cd 源码
GRADLE_USER_HOME=~/.gradle ./gradlew MixinApk-debug -PuseProcessorCountAsThreadCount=true --offline
```

随后按 BUILD.md 的步骤生成最终 5-dex 安装包。

## 调试

- 在设置里打开「启用日志」（或直接写入 `shared_prefs/qqpro.xml` 的 `enableLog`）后，调试日志写入
  `/sdcard/Android/data/com.tencent.qqlite/cache/qqpro_debug.log`；
- 崩溃/卡死会被内置 watchdog 捕获并弹出报告页（独立 `:crash` 进程渲染），报告也存于 `files/qqpro_crash_report.txt`；
- 详细排查方法见 `文档/API19-COMPAT.md`。
