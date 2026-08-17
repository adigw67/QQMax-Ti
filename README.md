# QQMax-Ti

> 面向儿童手表（Android 4.4.4 / API 19）的 QQ 增强改版，基于***QQMax***

> **⚠️ 法律声明**：本项目与腾讯公司无任何关联。使用本软件可能违反腾讯服务条款并导致账号风险，请自行评估。本仓库源代码依据 [LICENSE](LICENSE)（GNU GPL v3）授权；下载、安装或使用官方发行包即视为同意 [EULA.md](EULA.md) 的全部条款。

## 重要：如何安装
- 首先，请确保你的设备已经**root**并且安装**xporsed**框架
- 确保你的设备已经安装**核心破解**（corepatch）并且已经**激活**（本仓库提供了一个兼容安卓4的核心破解版本，即“核心破解_1.4(安卓4.x 5.x 6.x).apk“
- 接下来，直接安装没有**自行签名**的apk，之前安装的corepatch 就是为了安装ta
- 最后，选择风格，登录，使用

## 这是什么

QQMax-Ti 在原版手表 QQ（`com.tencent.qqlite`）的基础上，通过自研的 **ApkMixin** 工具把 Kotlin 编写的功能注入到原版字节码里：

- **不改原版 APK 的签名**（保留腾讯 META-INF），老设备可直接覆盖安装；
- 聊天、空间（说说）、联系人、资料卡等界面整体重做为 **Material 3 风格**；
- 补齐了大量手表端缺失或难用的功能（完整输入、表情、语音、大表情、群管理、说说等）；
- 所有 hook 逻辑均在本项目的 `app/src/main/java/momoi/` 与 `app/src/main/java/com/tencent/` 中实现，构建时再注入原版。

当前版本：**M2.5-4.4（v22 / API19fix）**，包名 `com.tencent.qqlite`，构建日期 2026-08-10。

**v17 新增**：设置 → 外观主题 →「联网字体包」——从官方源下载 MiSans（优先显示）+ GNU Unifont（生僻字兜底），仅本应用进程内生效，防生僻字/扩展区字形缺失。

**v18 修复**：字体覆盖判定改为构建期从官方 MiSans cmap 生成的精确 BMP 位图（8KB 内嵌、O(1) 查表），去掉运行时解析 8MB 字体文件——修复部分手表上「常见中文也被判为缺字、整屏退回 Unifont 点阵」的问题。

**v20 优化**：
- 覆盖判定补全 MiSans 扩展区（>BMP，180 组升序区间二分，如 U+20087 也用 MiSans）；扫描走全 BMP 逐 char 位图快路径，`applyAll`/`fallback` 单次扫描不再重复遍历；
- `installed()` 3 秒 TTL 缓存：聊天列表滚动/消息绑定/视图树遍历不再每行每视图做 2 次字体文件 stat；
- 会话行卡片背景与在线状态点改为 constant-state 原型克隆，滚动时不再每行新建 Drawable；
- 置顶图标资源 ID 缓存，去掉每行绑定的 `getIdentifier` 慢查询。

**v21 修复**：组装顺序 bug——`BoostMultiDex` 空壳被随后的 `WatchApplication` 替换覆盖回真库，导致每次冷启动都运行 OptimizeService 做 5-dex 优化（弱手表上 >10s ANR、CPU 满载、内存分页风暴，表现为「点聊天/群聊卡」）。现已改为替换后立即落盘，冷启动不再启动 OptimizeService，装机实测无 ANR、群聊 1s 打开。

**v22 修复**：深色模式下「我的」页操作横幅、群聊设置横幅整行纯白——`M3.ripple(null)` 在 API 19 的 `StateListDrawable` 回退把未按压默认态画成了白色 mask，所有 `M3ListItem` 行全白；默认态改为透明（与 API 21+ 一致），实测纯白占比 92% → 0%。

## 本目录内容

```
QQProMax-M2.5-4.4-发布包/
├── README.md                  ← 本文件（自述文档）
├── 安装包/
│   └── QQMax-Ti_M2.5-4.4-API19fix-v22.apk   ← 当前可安装包（5-dex，腾讯原签名保留）
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

## 已知问题

高版本可能存在的校验失败，设置全局背景后进入qq空间卡死

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
