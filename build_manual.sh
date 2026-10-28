#!/bin/bash
# ── ساخت APK بدون Gradle (روش دستی تثبیت‌شدهٔ ۱٫۵٫۰۱ تا ۱٫۵٫۰۳) ─────────────
set -eo pipefail
export JAVA_HOME=/home/user/.tools/jdk17
export PATH=$JAVA_HOME/bin:$PATH   # apksigner/d8 «java» را از PATH می‌گیرند، نه JAVA_HOME
SDK=/home/user/.tools/android-sdk
BT=$SDK/build-tools/34.0.0
AJ=$SDK/platforms/android-34/android.jar
SRC=/home/user/ST2w
W=/tmp/apk
VC=${1:-69}
VN=${2:-1.5.03}
rm -rf $W && mkdir -p $W/gen $W/classes $W/kclasses $W/dex
cd $SRC

# ۱) منابع
$BT/aapt2 compile --dir app/src/main/res -o $W/res.zip

# ۲) مانیفست با package (aapt2 لازمش دارد، گریدل خودش می‌گذاشت)
sed 's|<manifest xmlns:android="http://schemas.android.com/apk/res/android">|<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.alisport.goldpin">|' \
  app/src/main/AndroidManifest.xml > $W/AndroidManifest.xml

# ۳) لینک → base.apk + R.java
$BT/aapt2 link -o $W/base.apk -I $AJ --manifest $W/AndroidManifest.xml \
  -R $W/res.zip -A app/src/main/assets --java $W/gen \
  --min-sdk-version 24 --target-sdk-version 34 \
  --version-code $VC --version-name $VN --auto-add-overlay

# ۴) R.java
javac -nowarn -cp $AJ -d $W/classes $(find $W/gen -name '*.java') 2>&1 | grep -v "^Note" || true

# ۵) کاتلین: موتور + اپ
/tmp/kotlinc/bin/kotlinc -J-Xmx1150m -jvm-target 11 -nowarn -include-runtime \
  -cp "$AJ:$W/classes" engine/src/main/kotlin app/src/main/kotlin -d $W/kclasses 2>&1 | grep -v "^warning:" || true

# ۶) dex
# stdlib جدا لازم است: با خروجی «دایرکتوری»، -include-runtime کاری نمی‌کند
KSTD=/tmp/kotlinc/lib/kotlin-stdlib.jar
$BT/d8 --release --min-api 24 --lib $AJ --output $W/dex \
  $(find $W/classes $W/kclasses -name '*.class') $KSTD

# ۷) dex داخل APK + تراز + امضا
cd $W && cp base.apk un.zip && zip -q -j un.zip dex/classes.dex && mv un.zip unaligned.apk
$BT/zipalign -f -p 4 unaligned.apk aligned.apk
$BT/apksigner sign \
  --ks $SRC/keystore/goldpin.jks --ks-pass pass:goldpin1384 \
  --key-pass pass:goldpin1384 --ks-key-alias goldpin \
  --out /home/user/GoldPin-${VN}-${VC}.apk aligned.apk
$BT/apksigner verify --print-certs /home/user/GoldPin-${VN}-${VC}.apk | head -4
ls -l /home/user/GoldPin-${VN}-${VC}.apk
