# 构建与 5-dex 组装文档

## 1. 环境

- JDK 21（本机 `/usr/lib/jvm/...`，`java -version` 可查）
- Android SDK build-tools 35.0.0（`~/Android/Sdk/build-tools/35.0.0/`，含 `aapt`/`apksigner`/`dexdump`）
- Gradle 8.x（项目自带 wrapper，离线模式）
- 原版 QQ 手表 APK：***自行准备Nwearqq最终版2未去签版本***，放到 `源码/app/mixin/source.apk`（本发布包不包含）

## 2. 构建 Mixin

```bash
cd 源码
GRADLE_USER_HOME=/home/adugw/.gradle ./gradlew MixinApk-debug -PuseProcessorCountAsThreadCount=true --offline
```

产出：

| 产物 | 说明 |
| --- | --- |
| `app/build/mixinDex/classes*.dex` | 完整重写的目标类集合（20 个 dex，含新增类） |
| `app/dist/mixin.apk` | 原版 APK + 新 dex + 注入资源 + 合并清单（保留腾讯原签名，可作组装底包） |
| `app/dist/unsigned.apk` | 未签名变体 |

## 3. 重新生成 targets 清单（147 条）

`BuildFinal2` 只替换 targets 里列出的类。正确清单 = **原版 4 dex 与旧版已验证构建之间字节码有差异的类**
（不是全量交集——全量替换会把核心类挤进 classes5 导致启动死锁）。

```bash
# 提取原版 4 dex（source.apk 或 桌面/2.apk）
mkdir -p /tmp/orig && cd /tmp/orig && unzip -o -q 源码/app/mixin/source.apk 'classes*.dex'

# 提取"已验证正常"的旧版 4 dex（如 QQProMax_M2.5 第二版.apk）作为差异基准
mkdir -p /tmp/old_dex && cd /tmp/old_dex && unzip -o -q '/path/to/第二版.apk' 'classes*.dex'

# CompareDex：比较方法指令指纹，输出 DIFF 类
java -cp "/home/adugw/.codex/tmp/qqpm_build:smali-dexlib2-3.0.9.jar:multidexlib2-3.0.9.r4.jar" \
  CompareDex /tmp/orig/classes.dex /tmp/orig/classes2.dex /tmp/orig/classes3.dex /tmp/orig/classes4.dex \
  -- /tmp/old_dex/classes.dex /tmp/old_dex/classes2.dex /tmp/old_dex/classes3.dex /tmp/old_dex/classes4.dex \
  2>/dev/null | grep '^DIFF' | sed 's/^DIFF //' | sort -u > /tmp/all_targets_full.txt

# 146 条差异类 + OptimizeService = 147
cp /tmp/all_targets_full.txt /tmp/all_targets.txt
grep -q "OptimizeService" /tmp/all_targets.txt || \
  echo "Lcom/bytedance/boost_multidex/OptimizeService;" >> /tmp/all_targets.txt
wc -l /tmp/all_targets.txt   # 期望 147
```

> `CompareDex` 等工具类源码在 `源码/tools/` 与本次会话临时目录
> （`/home/adugw/.codex/tmp/qqpm_build/`），依赖 `smali-dexlib2-3.0.9.jar` 与
> `multidexlib2-3.0.9.r4.jar`（Gradle 缓存中有，或用 `ApkMixin-gen-dep`）。

## 4. 打多 dex 补丁（每次构建后都要重打）

`MixinApk-debug` 每次会重新生成 mixinDex，以下两个类需要从已验证版本换入（防止双套多 dex 竞争）：

```bash
CP="/home/adugw/.codex/tmp/qqpm_build:smali-dexlib2-3.0.9.jar:multidexlib2-3.0.9.r4.jar:/usr/share/java/guava-32.0.1-jre.jar"

# 定位当前 mixinDex 里两个类所在的 dex 文件
for f in app/build/mixinDex/classes*.dex; do
  dexdump "$f" | grep -q "boost_multidex/BoostMultiDex;" && echo "Boost: $f"
  dexdump "$f" | grep -q "watch/app/WatchApplication;" && echo "WatchApp: $f"
done

# 用旧版（已验证）字节码替换
java -cp "$CP" SwapClass <Boost所在dex> /tmp/old_dex/classes.dex <输出.dex> 'Lcom/bytedance/boost_multidex/BoostMultiDex;'
java -cp "$CP" SwapClass <WatchApp所在dex> /tmp/old_dex/classes.dex <输出.dex> 'Lcom/tencent/qqnt/watch/app/WatchApplication;'
# 移回原文件名；清理 SwapClass 留下的 *.dir 残留
```

替换后验证：`BoostMultiDex.install` 应为 7 指令空壳；`WatchApplication` 内应含 `jm_` 字符串。

## 5. BuildFinal2 + MergeExtra

```bash
mkdir -p /tmp/buildwork && cd /tmp/buildwork
cp /tmp/orig/classes*.dex .

java -cp "/home/adugw/.codex/tmp/qqpm_build:smali-dexlib2-3.0.9.jar:multidexlib2-3.0.9.r4.jar:/usr/share/java/guava-32.0.1-jre.jar" \
  BuildFinal2 /tmp/buildwork 源码/app/build/mixinDex/classes*.dex
# 期望输出：modified targets found: 147；orig1..4 替换数 15/25/107/0；new classes 2130

java -cp "同上" MergeExtra out_final2/new/classes.dex out_final2/orig2/classes2.dex out_final2/orig3/classes2.dex
# 产出 out_merged_extra/classes.dex → 最终 classes5（约 2419 类）
```

## 6. 组装最终 5-dex 安装包

```bash
cd /tmp/buildwork
cp 源码/app/dist/mixin.apk QQPM-final.apk
zip -q QQPM-final.apk -d 'classes*.dex'          # 移除 mixin.apk 内的 dex（保留 META-INF 签名！）
mkdir -p f5
cp out_final2/orig1/classes.dex f5/classes.dex
cp out_final2/orig2/classes.dex f5/classes2.dex
cp out_final2/orig3/classes.dex f5/classes3.dex
cp out_final2/orig4/classes.dex f5/classes4.dex
cp out_merged_extra/classes.dex f5/classes5.dex
cd f5 && zip -q ../QQPM-final.apk classes.dex classes2.dex classes3.dex classes4.dex classes5.dex
```

> **必须**用 `zip -d` + `zip` 替换 dex，而不是重新签名：老 ROM 校验签名证书不校验条目摘要，
> 保留腾讯 META-INF 字节原样才能覆盖安装且不丢账号体系。

## 7. 装机前校验

```bash
unzip -t QQPM-final.apk                                  # zip 完整性
unzip -l QQPM-final.apk | grep -E 'classes[0-9]*\.dex'   # 5-dex 布局
unzip -p QQPM-final.apk META-INF/ANDROIDR.RSA | sha256sum
unzip -p 源码/app/mixin/source.apk META-INF/ANDROIDR.RSA | sha256sum   # 必须一致（签名保留）
aapt dump badging QQPM-final.apk | grep -oE 'application-debuggable|package: name'  # debuggable

# M3 签名一致性（PreciseCheck：DEF/REF ripple$default 必须同为 Drawable）
java -cp "/tmp:/home/adugw/.codex/tmp/qqpm_build:multidexlib2-3.0.9.r4.jar:smali-dexlib2-3.0.9.jar" \
  PreciseCheck f5/classes5.dex
```

## 8. 安装与首启

```bash
adb -s <设备> push QQPM-final.apk /data/local/tmp/
adb -s <设备> shell am force-stop com.tencent.qqlite
adb -s <设备> shell pm install -r /data/local/tmp/QQPM-final.apk

# 清掉旧多 dex 缓存（避免加载旧 dex）
adb -s <设备> shell "su -c 'rm -rf /data/data/com.tencent.qqlite/files/jm_* /data/data/com.tencent.qqlite/files/boost_multidex /data/data/com.tencent.qqlite/files/javamultidex*'"

adb -s <设备> shell am start -n com.tencent.qqlite/com.tencent.qqnt.watch.app.JumpActivity
```

首启会做 5 个 dex 的 DexOpt（老手表 2~5 分钟），期间可能出现 ANR 弹窗，点"等待"即可；二次启动应显著加快。
