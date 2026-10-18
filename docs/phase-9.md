# Phase 9 — iPhone の撮影ボタン

IPA は GitHub Actions の `macos-latest` が `xcodebuild` で作る。Windows の Sideloadly が無料の Apple ID で署名して iPhone に入れる。手順は [cloud-ci.md](cloud-ci.md)。最小は iOS 17。

アプリは起動すると `ShodoGhost` を探し、HELLO を送る。画面が「準備完了」になったら「撮影」が押せる。中身は Command への `CAPTURE_NOW`（理由は電話、品質 90）で、nRF Connect の2通目と同じです。撮れたバイト数を画面に出します。JPEG の受信はまだ始めません。

IPA 用のワークフローは `.github/workflows/ios-ipa.yml`。GitHub へ push するまで Mac では走らない。この環境には Xcode が無いので、IPA はまだ無い。
