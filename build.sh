#!/usr/bin/env bash
set -euo pipefail

root="$(cd "$(dirname "$0")" && pwd)"
sdk="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$sdk" ]; then
  echo "ANDROID_SDK_ROOT/ANDROID_HOME non defini" >&2
  exit 1
fi
bt="$sdk/build-tools/36.0.0"
androidJar="$sdk/platforms/android-37.0/android.jar"
xposedJar="$root/xposed-api-82.jar"
jdk="${JAVA_HOME:-}"

src="$root/app/src/main/java"
assets="$root/app/src/main/assets"
manifest="$root/app/src/main/AndroidManifest.xml"

out="$root/build"
classes="$out/classes"
dex="$out/dex"
keystore="$out/module.keystore"
final="$out/launcher3mod-pixel.apk"

rm -rf "$classes" "$dex"
mkdir -p "$classes" "$dex"

echo "[1/6] javac"
mapfile -t sources < <(find "$src" -name '*.java')
javac --release 11 -encoding UTF-8 -cp "$androidJar:$xposedJar" -d "$classes" "${sources[@]}"

echo "[2/6] d8"
mapfile -t classFiles < <(find "$classes" -name '*.class')
"$bt/d8" --min-api 31 --lib "$androidJar" --lib "$xposedJar" --output "$dex" "${classFiles[@]}"

echo "[3/6] aapt2 link"
base="$out/base.apk"
"$bt/aapt2" link -o "$base" -I "$androidJar" --manifest "$manifest" -A "$assets" --auto-add-overlay

echo "[4/6] ajout classes.dex"
cp "$dex/classes.dex" "$out/classes.dex"
( cd "$out" && jar uf "$base" classes.dex )

echo "[5/6] zipalign"
aligned="$out/aligned.apk"
"$bt/zipalign" -f 4 "$base" "$aligned"

echo "[6/6] signature"
if [ ! -f "$keystore" ]; then
  keytool -genkeypair -v -keystore "$keystore" -alias module -keyalg RSA -keysize 2048 -validity 10000 -storepass bricks -keypass bricks -dname "CN=Launcher3Mod"
fi
"$bt/apksigner" sign --ks "$keystore" --ks-key-alias module --ks-pass pass:bricks --key-pass pass:bricks --out "$final" "$aligned"

echo ""
echo "OK -> $final"
"$bt/apksigner" verify --print-certs "$final"
echo "Taille : $(du -k "$final" | cut -f1) Ko"