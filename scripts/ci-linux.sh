#!/usr/bin/env bash
# Ubuntu で実行できる契約テスト。iPhone アプリの xcodebuild はここには入れない。
set -euo pipefail
cd "$(dirname "$0")/.."
chmod +x ./gradlew
./gradlew :protocol:test --no-daemon
swift test --package-path ios/Protocol
