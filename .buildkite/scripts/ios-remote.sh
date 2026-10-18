#!/usr/bin/env bash
# Mac 上で iPhone アプリをコンパイルする。署名が無いときはシミュレータ用の .app だけを残す。
set -euo pipefail

root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root"
mkdir -p build

xcodebuild -version
swift --version

echo "---- simulator ----"
xcodebuild \
  -project ios/App/ShodoGhost.xcodeproj \
  -scheme ShodoGhost \
  -configuration Debug \
  -sdk iphonesimulator \
  -destination "generic/platform=iOS Simulator" \
  -derivedDataPath "$root/build/ios-derived" \
  CODE_SIGNING_ALLOWED=NO \
  CODE_SIGNING_REQUIRED=NO \
  CODE_SIGN_IDENTITY="" \
  DEVELOPMENT_TEAM="" \
  build

app=""
while IFS= read -r path; do
  app="$path"
  break
done < <(find "$root/build/ios-derived/Build/Products" -type d -name "ShodoGhost.app")
test -n "$app"
ditto -c -k --keepParent "$app" "$root/build/ShodoGhost-simulator.zip"
if command -v buildkite-agent >/dev/null 2>&1; then
  buildkite-agent artifact upload "build/ShodoGhost-simulator.zip"
fi

echo "---- swift test ----"
swift test --package-path ios/Protocol

echo "---- device ----"
missing=()
for name in APPLE_TEAM_ID APP_STORE_CONNECT_KEY_ID APP_STORE_CONNECT_ISSUER_ID APP_STORE_CONNECT_KEY; do
  if [[ -z "${!name:-}" ]]; then
    missing+=("$name")
  fi
done
if ((${#missing[@]})); then
  echo "実機用の IPA は作らない。Secrets に無いもの: ${missing[*]}"
  echo "シミュレータ用は build/ShodoGhost-simulator.zip"
  exit 0
fi

key_file="$(mktemp)"
chmod 600 "$key_file"
cleanup() { rm -f "$key_file"; }
trap cleanup EXIT
python3 -c 'import os, sys, base64
raw = os.environ["APP_STORE_CONNECT_KEY"]
if "BEGIN PRIVATE KEY" in raw and "\\n" in raw and "\n" not in raw:
    raw = raw.replace("\\n", "\n")
data = raw.encode() if "BEGIN PRIVATE KEY" in raw else base64.b64decode(raw)
if not data.endswith(b"\n"):
    data += b"\n"
sys.stdout.buffer.write(data)' >"$key_file"

auth_flags=(
  -allowProvisioningUpdates
  -authenticationKeyPath "$key_file"
  -authenticationKeyID "$APP_STORE_CONNECT_KEY_ID"
  -authenticationKeyIssuerID "$APP_STORE_CONNECT_ISSUER_ID"
)

xcodebuild \
  -project ios/App/ShodoGhost.xcodeproj \
  -scheme ShodoGhost \
  -configuration Release \
  -destination "generic/platform=iOS" \
  -archivePath "$root/build/ShodoGhost.xcarchive" \
  "${auth_flags[@]}" \
  DEVELOPMENT_TEAM="$APPLE_TEAM_ID" \
  CODE_SIGN_STYLE=Automatic \
  archive

method="development"
destination="export"
if [[ "${IOS_DISTRIBUTE:-}" == "testflight" ]]; then
  method="app-store-connect"
  destination="upload"
fi

plist="$root/build/exportOptions.plist"
cat >"$plist" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>method</key>
  <string>${method}</string>
  <key>destination</key>
  <string>${destination}</string>
  <key>teamID</key>
  <string>${APPLE_TEAM_ID}</string>
  <key>signingStyle</key>
  <string>automatic</string>
  <key>manageAppVersionAndBuildNumber</key>
  <true/>
</dict>
</plist>
EOF

rm -rf "$root/build/ipa"
xcodebuild \
  -exportArchive \
  -archivePath "$root/build/ShodoGhost.xcarchive" \
  -exportPath "$root/build/ipa" \
  -exportOptionsPlist "$plist" \
  "${auth_flags[@]}"

if [[ "$destination" == "upload" ]]; then
  echo "App Store Connect へアップロードした。iPhone では TestFlight から入れる。"
  exit 0
fi

ipa=""
while IFS= read -r path; do
  ipa="$path"
  break
done < <(find "$root/build/ipa" -name "*.ipa")
test -n "$ipa"
cp "$ipa" "$root/build/ShodoGhost.ipa"
if command -v buildkite-agent >/dev/null 2>&1; then
  buildkite-agent artifact upload "build/ShodoGhost.ipa"
fi
echo "development IPA を build/ShodoGhost.ipa に置いた。この Team に UDID を登録した iPhone に入る。"
