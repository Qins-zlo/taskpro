#!/bin/sh
# 定时任务Pro 构建脚本 (aarch64 兼容版)
set -e
APP=/root/taskpro_v732/taskpro
SRC=$APP/src
OUT=$APP/out
DEX=$APP/dex
GEN=$APP/gen
ANDROID_JAR=/opt/android-sdk/platforms/android-34/android.jar
AAPT2=/opt/android-sdk/build-tools/34.0.0/aapt2
D8JAR=/opt/android-sdk/build-tools/34.0.0/lib/d8.jar
APKSIGNER=/opt/android-sdk/build-tools/34.0.0/lib/apksigner.jar
ZALIGN=/root/taskpro_v732/zipalign.py
KEYSTORE=$APP/taskrun.keystore
# ── 安全: 构建期间注入的 token 必须无条件恢复 ──
# 备份文件放在 /tmp (避免 rm -rf OUT 清掉); 用 trap 保证任何退出路径(含编译失败)都恢复源码
TOKEN_BK=/tmp/Backend.java.bak.$$
restore_backend() {
  if [ -f "$TOKEN_BK" ]; then
    cp "$TOKEN_BK" "$SRC/io/taskpro/Backend.java"
    rm -f "$TOKEN_BK"
    echo "  已恢复源码 (移除 token)"
  fi
}
rm -rf $OUT $DEX 2>/dev/null; mkdir -p $OUT $DEX

echo "[1/6] aapt2 compile"
$AAPT2 compile -o $OUT/res.flata --dir $APP/res 2>/dev/null || true

echo "[2/6] aapt2 link"
$AAPT2 link -o $OUT/base.apk \
  -I $ANDROID_JAR \
  --manifest $APP/AndroidManifest.xml \
  --java $GEN \
  -A $APP/assets \
  -0 gz \
  $OUT/res.flata 2>/dev/null || {
  echo "link fallback without assets"
  $AAPT2 link -o $OUT/base.apk \
    -I $ANDROID_JAR \
    --manifest $APP/AndroidManifest.xml \
    --java $GEN \
    $OUT/res.flata 2>/dev/null
}

echo "[3/6] javac"
# 构建时注入 GitHub bot token: 从环境变量 GH_BOT_TOKEN 替换源码占位符
# (保证公开仓库源码不含真实 token; 编译后自动恢复)
BK=$TOKEN_BK
if [ -n "$GH_BOT_TOKEN" ]; then
  cp $SRC/io/taskpro/Backend.java $BK
  trap 'restore_backend' EXIT INT TERM HUP   # 任何退出路径都恢复, 防止 token 残留泄露
  sed -i "s/REPLACE_WITH_BUILD_INJECTED_TOKEN/$GH_BOT_TOKEN/g" $SRC/io/taskpro/Backend.java
  echo "  注入 GH_BOT_TOKEN (len=${#GH_BOT_TOKEN})"
fi
find $SRC -name '*.java' > $OUT/sources.txt
javac -d $DEX -cp $ANDROID_JAR -encoding UTF-8 \
  -source 11 -target 11 \
  @$OUT/sources.txt $GEN/io/taskpro/R.java 2>&1
restore_backend
echo "[4/6] d8"
java -cp $D8JAR com.android.tools.r8.D8 \
  --lib $ANDROID_JAR \
  --release \
  --output $DEX \
  $(find $DEX -name '*.class' 2>/dev/null)

python3 - "$APP" <<'PY'
import sys, zipfile
app = sys.argv[1]
with zipfile.ZipFile(app + "/out/base.apk", 'a', zipfile.ZIP_DEFLATED) as z:
    z.write(app + "/dex/classes.dex", "classes.dex")
print("classes.dex injected")
PY

echo "[5/6] add lib (STORED)"
python3 - "$APP" <<'PY'
import sys, zipfile, os
app = sys.argv[1]
libdir = app + "/lib/arm64-v8a"
if not os.path.isdir(libdir):
    print("!! no lib dir, skip")
    sys.exit(0)
with zipfile.ZipFile(app + "/out/base.apk", 'a', zipfile.ZIP_STORED) as z:
    existing = set(z.namelist())
    n = 0
    for f in sorted(os.listdir(libdir)):
        full = os.path.join(libdir, f)
        arc = "lib/arm64-v8a/" + f
        if arc in existing:
            continue
        z.write(full, arc)
        n += 1
    print(f"added {n} native libs")
PY

echo "[6/6] zipalign + sign"
python3 $ZALIGN $OUT/base.apk $OUT/aligned.apk 4

if [ ! -f $KEYSTORE ]; then
  keytool -genkeypair -keystore $KEYSTORE -storepass task123 -alias tr \
    -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=TaskRunner,O=AI,C=CN" 2>/dev/null
fi

java -jar $APKSIGNER sign --ks $KEYSTORE --ks-pass pass:task123 --ks-key-alias tr \
  --out $OUT/taskpro.apk $OUT/aligned.apk 2>&1

echo ""
java -jar $APKSIGNER verify $OUT/taskpro.apk 2>&1 | head -3
echo ""
echo "BUILD_OK"
ls -lh $OUT/taskpro.apk