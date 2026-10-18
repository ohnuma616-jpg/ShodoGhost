#!/usr/bin/env bash
# GitHub Actions の macOS で、Sideloadly に渡す実機用 IPA を作る。
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root"
rm -rf build/Payload build/ios-derived build/ShodoGhost.ipa
mkdir -p build/Payload

xcodebuild -version
xcodebuild \
  -project ios/App/ShodoGhost.xcodeproj \
  -scheme ShodoGhost \
  -configuration Release \
  -sdk iphoneos \
  -destination "generic/platform=iOS" \
  -derivedDataPath "$root/build/ios-derived" \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGN_IDENTITY="" \
  DEVELOPMENT_TEAM="" \
  VALIDATE_PRODUCT=NO \
  build

app=""
while IFS= read -r path; do
  app="$path"
  break
done < <(find "$root/build/ios-derived/Build/Products" -type d -name "ShodoGhost.app")
test -n "$app"
cp -R "$app" "$root/build/Payload/ShodoGhost.app"
codesign --force --sign - --timestamp=none "$root/build/Payload/ShodoGhost.app"
(cd "$root/build" && zip -r -y ShodoGhost.ipa Payload)
echo "IPA: $root/build/ShodoGhost.ipa"
echo "署名は Sideloadly が Apple ID で付ける。"
