#!/bin/sh
# Keep For Days — gradle-free build (android-minimal-apk pattern).
# No aars, no aidl; single-module offline build.
# Local debug keystore lives in this dir: same-key builds update in place.
# The PHONE runs CI-signed builds (cert 267b7543…db56f2, keystore in
# Bitwarden) — sign with KSTORE=<ci-keystore> KSTOREPASS=<pw> for an
# in-place-update artifact.
set -eu

export JAVA_HOME="${JAVA_HOME:-$HOME/.local/share/jdk17}"
export PATH="$JAVA_HOME/bin:$PATH"
SDK="${ANDROID_SDK:-$HOME/.local/share/android-sdk}"
# v1.12.0: toolchain overridable for hosts with only newer build-tools
# (CI keeps the pinned 34.0.0/android-34; local builds may pass BT/PLATFORM).
BT="${BT:-$SDK/build-tools/34.0.0}"
PLATFORM="${PLATFORM:-$SDK/platforms/android-34/android.jar}"
VER_NAME=1.0.0
VER_CODE=1
KSTORE="${KSTORE:-debug.keystore}"
KSTOREPASS="${KSTOREPASS:-android}"
OUT="INSTALL-ME-keep-for-days-$VER_NAME.apk"

rm -rf build
mkdir -p build/classes build/dex build/gen

echo "== aapt2 compile/link =="
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -o build/base.apk -I "$PLATFORM" \
    --manifest AndroidManifest.xml \
    --java build/gen \
    --min-sdk-version 29 --target-sdk-version 34 \
    --version-code "$VER_CODE" --version-name "$VER_NAME" \
    build/res.zip

echo "== javac (no lambdas: bootclasspath build) =="
find src build/gen -name '*.java' > build/sources.txt
javac -source 1.8 -target 1.8 -Xlint:-options \
    -bootclasspath "$PLATFORM" \
    -d build/classes @build/sources.txt

echo "== d8 =="
"$BT/d8" --release --min-api 29 --lib "$PLATFORM" --output build/dex \
    $(find build/classes -name '*.class')

cp build/dex/classes.dex build/classes.dex
# v1.12.0: zip may be absent on minimal hosts — python3 fallback.
if command -v zip >/dev/null 2>&1; then
    (cd build && zip -q -j base.apk classes.dex)
else
    python3 -c "import sys, zipfile; z = zipfile.ZipFile(sys.argv[1], 'a'); z.write(sys.argv[2], 'classes.dex'); z.close()" \
        build/base.apk build/classes.dex
fi

echo "== zipalign + sign =="
"$BT/zipalign" -f 4 build/base.apk build/aligned.apk

if [ ! -f "$KSTORE" ]; then
    echo "  (no keystore at $KSTORE: generating one — same-key builds update in place)"
    keytool -genkeypair -keystore "$KSTORE" \
        -dname "CN=KeepForDays Debug,O=devinslick,C=US" \
        -alias androiddebugkey -storepass android -keypass android \
        -keyalg RSA -keysize 2048 -validity 10000
fi

"$BT/apksigner" sign --ks "$KSTORE" --ks-pass "pass:$KSTOREPASS" \
    --out "$OUT" build/aligned.apk

echo "== verify =="
"$BT/apksigner" verify --print-certs "$OUT" > build/certs.txt
"$BT/aapt2" dump badging "$OUT" > build/badging.txt
unzip -l "$OUT" | grep -c classes.dex
sed -n '1,4p' build/certs.txt
sed -n '1,3p' build/badging.txt
unzip -o "$OUT" "classes*.dex" -d build/chk >/dev/null
python3 - <<EOF
import glob, sys
hits = 0
for f in glob.glob('build/chk/classes*.dex'):
    hits += open(f, 'rb').read().count(b'KeepForDays $VER_NAME start')
print('marker occurrences:', hits)
sys.exit(0 if hits >= 1 else 1)
EOF
echo "OK: $OUT"
