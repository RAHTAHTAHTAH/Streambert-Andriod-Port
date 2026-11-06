#!/usr/bin/env bash
# Streambert Mobile - APK build script for Linux/macOS (no Gradle, no Android Studio).
#
# Mirrors build.ps1: it drives aapt2, javac, d8, zipalign and apksigner directly.
#
# Requirements:
#   - JDK 17 or 21 (JAVA_HOME)
#   - Android SDK with a platform (android.jar) and build-tools 35+
#   - On aarch64 hosts the Linux build-tools binaries are x86-64; install
#     qemu-user-static and they are run transparently through it.
#
# Usage:
#   ./build.sh
#   ANDROID_HOME=~/Android/Sdk JAVA_HOME=/usr/lib/jvm/java-21-openjdk ./build.sh
set -euo pipefail

root="$(cd "$(dirname "$0")" && pwd)"

# --- Locate a JDK ----------------------------------------------------------
if [ -n "${JAVA_HOME:-}" ] && [ -x "$JAVA_HOME/bin/javac" ]; then
  jdk="$JAVA_HOME"
elif command -v javac >/dev/null 2>&1; then
  jdk="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
else
  echo "No JDK found. Set JAVA_HOME." >&2
  exit 1
fi

# --- Locate an Android SDK -------------------------------------------------
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -z "$sdk" ] || [ ! -d "$sdk/platforms" ]; then
  for candidate in "$HOME/Android/Sdk" /opt/android-sdk /usr/lib/android-sdk; do
    if [ -d "$candidate/platforms" ]; then sdk="$candidate"; break; fi
  done
fi
if [ -z "$sdk" ] || [ ! -d "$sdk/platforms" ]; then
  echo "No Android SDK found. Set ANDROID_HOME." >&2
  exit 1
fi

# --- Newest platform + build-tools ----------------------------------------
platform="$(find "$sdk/platforms" -maxdepth 1 -mindepth 1 -type d -name 'android-*' \
  -exec test -f '{}/android.jar' \; -print | sort -V | tail -1)"
if [ -z "$platform" ]; then
  echo "No Android platform (android.jar) installed in $sdk/platforms" >&2
  exit 1
fi
android_jar="$platform/android.jar"

bt="$(find "$sdk/build-tools" -maxdepth 1 -mindepth 1 -type d -print | sort -V | tail -1)"
if [ -z "$bt" ]; then
  echo "No build-tools installed in $sdk/build-tools" >&2
  exit 1
fi

# --- Run x86-64 tools on aarch64 through qemu -----------------------------
qemu=""
if [ "$(uname -m)" = "aarch64" ]; then
  if command -v qemu-x86_64-static >/dev/null 2>&1; then
    qemu="qemu-x86_64-static"
  elif command -v qemu-x86_64 >/dev/null 2>&1; then
    qemu="qemu-x86_64"
  fi
fi

run_tool() {
  local tool="$1"; shift
  if [ -n "$qemu" ]; then
    local prefix="${QEMU_LD_PREFIX:-}"
    if [ -n "$prefix" ]; then
      QEMU_LD_PREFIX="$prefix" "$qemu" "$tool" "$@"
    else
      "$qemu" "$tool" "$@"
    fi
  else
    "$tool" "$@"
  fi
}

# Java needs its own lib dir on some chroot/proot setups.
if ! java -version >/dev/null 2>&1; then
  export LD_LIBRARY_PATH="$jdk/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
fi
java_bin="$jdk/bin/java"
javac_bin="$jdk/bin/javac"
keytool_bin="$jdk/bin/keytool"

echo "JDK:      $jdk"
echo "SDK:      $sdk"
echo "Platform: $(basename "$platform")"
echo "Build:    $(basename "$bt")"

# Version info comes from the manifest so the two can never drift apart.
manifest="$root/AndroidManifest.xml"
version_code="$(sed -n 's/.*android:versionCode="\([0-9]*\)".*/\1/p' "$manifest" | head -1)"
version_name="$(sed -n 's/.*android:versionName="\([^"]*\)".*/\1/p' "$manifest" | head -1)"
if [ -z "$version_code" ] || [ -z "$version_name" ]; then
  echo "versionCode/versionName missing from AndroidManifest.xml" >&2
  exit 1
fi
echo "Version:  $version_name ($version_code)"

build="$root/build"
rm -rf "$build"
mkdir -p "$build/gen" "$build/classes" "$build/dex" "$build/stage"

echo "[1/7] Compiling resources..."
run_tool "$bt/aapt2" compile --dir "$root/res" -o "$build/res.zip"

echo "[2/7] Linking resources..."
run_tool "$bt/aapt2" link \
  -o "$build/base.apk" \
  -I "$android_jar" \
  --manifest "$manifest" \
  --java "$build/gen" \
  --min-sdk-version 23 \
  --target-sdk-version 34 \
  --version-code "$version_code" \
  --version-name "$version_name" \
  "$build/res.zip"

echo "[3/7] Compiling Java..."
mapfile -t sources < <(find "$root/java" -name '*.java')
mapfile -t generated < <(find "$build/gen" -name 'R.java')
"$javac_bin" -source 8 -target 8 -Xlint:-options \
  -classpath "$android_jar" -d "$build/classes" "${sources[@]}" "${generated[@]}"

echo "[4/7] Dexing..."
mapfile -t classes < <(find "$build/classes" -name '*.class')
"$java_bin" -cp "$bt/lib/d8.jar" com.android.tools.r8.D8 \
  --lib "$android_jar" --min-api 23 --output "$build/dex" "${classes[@]}"

echo "[5/7] Packaging assets + classes.dex..."
cp "$build/base.apk" "$build/unsigned.apk"
cp -a "$root/assets" "$build/stage/assets"
cp "$build/dex/classes.dex" "$build/stage/classes.dex"
(cd "$build/stage" && zip -q -r "$build/unsigned.apk" assets classes.dex)

run_tool "$bt/zipalign" -f 4 "$build/unsigned.apk" "$build/aligned.apk"

echo "[6/7] Signing..."
ks="$root/streambert-debug.keystore"
if [ ! -f "$ks" ]; then
  "$keytool_bin" -genkeypair -v \
    -keystore "$ks" -storepass android -alias androiddebugkey -keypass android \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Streambert Mobile Debug,O=Streambert,C=US"
fi
"$java_bin" -jar "$bt/lib/apksigner.jar" sign --ks "$ks" \
  --ks-pass pass:android --key-pass pass:android \
  --out "$root/Streambert-Mobile.apk" "$build/aligned.apk"

echo "[7/7] Verifying..."
"$java_bin" -jar "$bt/lib/apksigner.jar" verify --print-certs "$root/Streambert-Mobile.apk"

size="$(du -h "$root/Streambert-Mobile.apk" | cut -f1)"
echo ""
echo "Done: $root/Streambert-Mobile.apk ($size)"
