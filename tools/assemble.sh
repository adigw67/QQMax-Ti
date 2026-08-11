#!/usr/bin/env bash
#
# 最终 5-dex 安装包组装脚本
# =========================
# 用法:
#   assemble.sh <orig_dir> <old_dir> <mixin_dir> <base_apk> <out_apk>
#
# 参数:
#   orig_dir  原版 QQ 的 4 个 dex 所在目录 (classes.dex..classes4.dex)
#   old_dir   已验证可用的旧版 5 dex 所在目录 (classes.dex..classes5.dex)，
#             用于生成 targets 清单与多 dex 补丁来源
#   mixin_dir MixinApk-debug 构建产物 (app/build/mixinDex)
#   base_apk  app/dist/mixin.apk（含腾讯原签名与注入资源）
#   out_apk   输出的最终安装包路径
#
# 环境变量（可选，默认按本机路径）:
#   DEXLIB2_JAR      smali-dexlib2 jar
#   MULTIDEXLIB2_JAR multidexlib2 jar
#   GUAVA_JAR        guava jar
#
set -euo pipefail

ORIG_DIR="${1:?orig_dir 必填}"
OLD_DIR="${2:?old_dir 必填}"
MIXIN_DIR="${3:?mixin_dir 必填}"
BASE_APK="${4:?base_apk 必填}"
OUT_APK="${5:?out_apk 必填}"

# 统一转绝对路径（脚本中途会 cd 到临时目录）
ORIG_DIR="$(cd "$ORIG_DIR" && pwd)"
OLD_DIR="$(cd "$OLD_DIR" && pwd)"
MIXIN_DIR="$(cd "$MIXIN_DIR" && pwd)"
BASE_APK="$(readlink -f "$BASE_APK")"
OUT_APK="$(readlink -f "$OUT_APK")"

TOOLS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEXLIB2_JAR="${DEXLIB2_JAR:-$HOME/.gradle/caches/modules-2/files-2.1/com.android.tools.smali/smali-dexlib2/3.0.9/ce8ab6d577bf076a67614c92157e92122b539dc3/smali-dexlib2-3.0.9.jar}"
MULTIDEXLIB2_JAR="${MULTIDEXLIB2_JAR:-$HOME/.gradle/caches/modules-2/files-2.1/com.huanli233/multidexlib2/3.0.9.r4/bd5bf65b91eaad7047199ffcee982e8f53f1b69b/multidexlib2-3.0.9.r4.jar}"
GUAVA_JAR="${GUAVA_JAR:-/usr/share/java/guava-32.0.1-jre.jar}"

WORK="$(mktemp -d /tmp/qqpm_assemble.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

echo "==> [0/6] 编译开发工具 (CompareDex/SwapClass/BuildFinal2/MergeExtra/...)"
DEV_CLASSES="$WORK/tools-classes"
mkdir -p "$DEV_CLASSES"
javac -cp "$DEXLIB2_JAR:$MULTIDEXLIB2_JAR" -d "$DEV_CLASSES" "$TOOLS_DIR"/*.java
CP="$DEV_CLASSES:$DEXLIB2_JAR:$MULTIDEXLIB2_JAR:$GUAVA_JAR"

echo "==> [1/6] 生成 targets 清单 (原版 4dex vs 旧版 4dex 差异 + OptimizeService)"
# BuildFinal2 硬编码读取 /tmp/all_targets.txt，这里直接写到该路径，脚本自洽。
TARGETS_FILE="/tmp/all_targets.txt"
java -cp "$CP" CompareDex \
  "$ORIG_DIR/classes.dex" "$ORIG_DIR/classes2.dex" "$ORIG_DIR/classes3.dex" "$ORIG_DIR/classes4.dex" -- \
  "$OLD_DIR/classes.dex" "$OLD_DIR/classes2.dex" "$OLD_DIR/classes3.dex" "$OLD_DIR/classes4.dex" \
  2>/dev/null | grep '^DIFF' | sed 's/^DIFF //' | sort -u > "$TARGETS_FILE"
grep -q 'Lcom/bytedance/boost_multidex/OptimizeService;' "$TARGETS_FILE" || \
  echo 'Lcom/bytedance/boost_multidex/OptimizeService;' >> "$TARGETS_FILE"
echo "    targets = $(wc -l < "$TARGETS_FILE")"

echo "==> [2/6] 定位并应用多 dex 补丁 (BoostMultiDex 空壳 + WatchApplication 按进程目录)"
for f in "$MIXIN_DIR"/classes*.dex; do
  # (dexdump || true): this ROM's /usr/bin/dexdump exits non-zero on warnings, which under
  # `set -o pipefail` would poison the pipeline even when grep matched.
  if (dexdump "$f" 2>/dev/null || true) | grep -q 'boost_multidex/BoostMultiDex;'; then BOOST_DEX="$f"; fi
  if (dexdump "$f" 2>/dev/null || true) | grep -q 'watch/app/WatchApplication;'; then WATCH_DEX="$f"; fi
done
BOOST_DEX="${BOOST_DEX:?mixinDex 中找不到 BoostMultiDex}"
WATCH_DEX="${WATCH_DEX:?mixinDex 中找不到 WatchApplication}"
java -cp "$CP" SwapClass "$BOOST_DEX" "$OLD_DIR/classes.dex" "$WORK/boost.dex" 'Lcom/bytedance/boost_multidex/BoostMultiDex;'
# 关键：必须先落盘 boost.dex 再处理 WatchApplication——两个 SwapClass 都读的是同一个
# classes2.dex，若先跑完两个 java 再统一 mv，第二个 mv 会把第一个的空壳覆盖回真库
# （v19/v20 的冷启动卡死/ANR 正是这个顺序 bug 导致 OptimizeService 仍在运行）。
mv "$WORK/boost.dex" "$BOOST_DEX"
java -cp "$CP" SwapClass "$WATCH_DEX" "$OLD_DIR/classes.dex" "$WORK/watch.dex" 'Lcom/tencent/qqnt/watch/app/WatchApplication;'
mv "$WORK/watch.dex" "$WATCH_DEX"
find "$MIXIN_DIR" -maxdepth 1 -name '*.dir' -exec rm -rf {} +

echo "==> [3/6] BuildFinal2（原版 4 dex + targets 替换）"
mkdir -p "$WORK/build"
cd "$WORK/build"
java -cp "$CP" BuildFinal2 "$ORIG_DIR" "$MIXIN_DIR"/classes*.dex 2>&1 | tail -6

echo "==> [4/6] MergeExtra（合并溢出为新 classes5）"
java -cp "$CP" MergeExtra out_final2/new/classes.dex out_final2/orig2/classes2.dex out_final2/orig3/classes2.dex 2>&1 | tail -2

echo "==> [5/6] 组装最终 APK"
cp "$BASE_APK" "$OUT_APK"
zip -q "$OUT_APK" -d 'classes*.dex'
mkdir -p f5
cp out_final2/orig1/classes.dex f5/classes.dex
cp out_final2/orig2/classes.dex f5/classes2.dex
cp out_final2/orig3/classes.dex f5/classes3.dex
cp out_final2/orig4/classes.dex f5/classes4.dex
cp out_merged_extra/classes.dex f5/classes5.dex
(cd f5 && zip -q "$OUT_APK" classes.dex classes2.dex classes3.dex classes4.dex classes5.dex)

echo "==> [6/6] 校验"
unzip -t "$OUT_APK" >/dev/null && echo "    ZIP OK"
unzip -l "$OUT_APK" | grep -E 'classes[0-9]*\.dex'
echo "完成: $OUT_APK"
