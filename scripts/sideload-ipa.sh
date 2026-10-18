#!/usr/bin/env bash
# iOS SDK が入った WSL で、Sideloadly に渡す未署名 IPA を作る。
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$root/ios/Phone"

if ! command -v xtool >/dev/null 2>&1; then
  echo "xtool が無い。docs/cloud-ci.md の WSL 手順で入れる。" >&2
  exit 1
fi
if ! swift sdk list 2>/dev/null | grep -q darwin; then
  echo "iOS SDK が無い。ブラウザで Xcode 27 の .xip を落とし、xtool setup にそのパスを渡す。" >&2
  exit 1
fi

xtool dev build --ipa

ipa=""
while IFS= read -r path; do
  ipa="$path"
  break
done < <(find "$root/ios/Phone" -type f -name "ShodoGhost.ipa")
if [[ -z "$ipa" ]]; then
  echo "ShodoGhost.ipa が見つからない。" >&2
  exit 1
fi

mkdir -p "$root/build"
cp "$ipa" "$root/build/ShodoGhost.ipa"
echo "IPA: $root/build/ShodoGhost.ipa"
echo "Windows の Sideloadly にこのファイルを落とす。署名は Sideloadly が Apple ID で付ける。"
