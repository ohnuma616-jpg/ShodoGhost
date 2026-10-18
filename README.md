# Shodo Ghost AR

RayNeo X3 / X3 Pro 向けの美文字・毛筆練習アシスタント。半紙の上に手本の線を重ね、書き終えた静止画をスマートフォン側で添削する。

今あるのは **Phase 11（赤い添削）** までです。640×480 の透過 HUD を 1280×480 の左右へ出し、グラスは `ShodoGhost` として広告します。iPhone アプリが接続し、撮影ボタンが `CAPTURE_NOW` を送ると、カメラ 0 を 1 枚開いて閉じます。その JPEG を iPhone のホットスポット越しに受け、画面に出します。そのあと赤の一画と2行を BLE でグラスへ返します。未送信の JPEG はメモリ 1 枚を再接続後に送り直します。確認用 Activity だけ、引き出すために `files/still.jpg` へ 1 枚書きます。iPhone 用の IPA は、GitHub の macOS で `xcodebuild` し、Windows の Sideloadly が署名して入れる。手順は [docs/cloud-ci.md](docs/cloud-ci.md)。

## このフェーズで固定したこと

- グラスは Kotlin。スマホは iPhone（Swift / SwiftUI、iOS 17）。
- パケットのバイト列は `:protocol`（グラス）と `ios/Protocol`（iPhone）の両方にあり、`fixtures/canonical.txt` で一致させる。
- グラスは GATT ペリフェラル、iPhone は CoreBluetooth のセントラル。
- 常時つなぐのは BLE。JPEG のときだけ WakeLock を取って Wi-Fi の WebSocket を短時間開く。
- 既定の表示は 0DoF の 2D HUD。6DoF / SLAM はアダプタの裏で、既定ビルドでは使わない。
- 背景 `#000000` は消灯（透過）。線は 2px 以上。文字は 640×480 の内側 10% に置く。
- グラス側に GMS、Firebase、地図 SDK、ソフトキーボードは置かない。

仕様書から変えた判断は [docs/spec-review.md](docs/spec-review.md) にまとめてあります。

## ドキュメント

| 文書 | 内容 |
| --- | --- |
| [docs/architecture.md](docs/architecture.md) | スタック、役割、アダプタ、電力 |
| [docs/phase-2.md](docs/phase-2.md) | セッション、モック GATT、ループバック |
| [docs/phase-3.md](docs/phase-3.md) | 透過 HUD、セーフエリア、テンプル操作 |
| [docs/phase-4.md](docs/phase-4.md) | 前面の練習画面、JPEG バースト、添削 |
| [docs/phase-5.md](docs/phase-5.md) | スリープ、再接続、ラジオを持ってよい相 |
| [docs/phase-6.md](docs/phase-6.md) | カメラ 0 のワンショットと、メモリに残す JPEG |
| [docs/phase-7.md](docs/phase-7.md) | 1280×480 の画面と、起動後 1 枚の撮影 |
| [docs/phase-8.md](docs/phase-8.md) | BLE 広告と、電話からの CAPTURE_NOW |
| [docs/phase-9.md](docs/phase-9.md) | iPhone の撮影ボタン |
| [docs/phase-10.md](docs/phase-10.md) | JPEG をホットスポット越しに iPhone の画面へ |
| [docs/phase-11.md](docs/phase-11.md) | 赤の一画と2行をグラスへ返す |
| [docs/cloud-ci.md](docs/cloud-ci.md) | GitHub Actions の Mac で IPA を作り、Sideloadly で入れる |
| [docs/iphone.md](docs/iphone.md) | iPhone の BLE バックグラウンド、Wi-Fi、権限 |
| [docs/spec-review.md](docs/spec-review.md) | 仕様書の見直し |
| [docs/ble-gatt.md](docs/ble-gatt.md) | Custom Service と Characteristic |
| [docs/packet-format.md](docs/packet-format.md) | フレーム、メッセージ、分割 |
| [docs/sequences.md](docs/sequences.md) | 通信シーケンス |
| [docs/hud-and-input.md](docs/hud-and-input.md) | セーフエリア、色、タッチ |
| [docs/directory-layout.md](docs/directory-layout.md) | ディレクトリと次フェーズの置き場 |
| [docs/reference-projects.md](docs/reference-projects.md) | FixLensAR と TapInsight。ソースは取り込まない |

## 動かし方

JDK 21。リポジトリ直下で:

```bash
./gradlew :protocol:test
```

テストは CRC、フレーム往復、制御メッセージが ATT MTU 185 の1フレームに収まること、筆画パックの分割と順不同再構成、半紙四隅の射影、リンク状態機械を見ます。

iPhone 側の同じ契約は Swift パッケージです。Swift 6 のツールチェーンで:

```bash
swift test --package-path ios/Protocol
```

このテストは `fixtures/canonical.txt` を復号し、再符号化して Kotlin のバイト列と一致することを確認します。セッション、HUD の入力、前面ゲート、TCP の JPEG バーストも同じコマンドに入っています。詳細は [docs/phase-4.md](docs/phase-4.md)。

グラス役の TCP と iPhone セッションを 1 本で往復させるときは:

```bash
./scripts/loopback-check.sh
```

詳細は [docs/phase-2.md](docs/phase-2.md)。

iPhone に入れる IPA は、GitHub の公開リポジトリで `macos-latest` が作る。Windows の Sideloadly にその IPA を落とす。手順は [docs/cloud-ci.md](docs/cloud-ci.md)。

## ハードウェア前提

RayNeo 公式資料（X3 Pro、2025年の開発者ドキュメント）に合わせています。

- Snapdragon AR1 Gen 1、RayNeo AIOS。接続した X3 Pro（ARGF20 / MercuryLiteXR）は `ro.build.version.release` 12、`ro.build.version.sdk` 32（Android 12L）
- 両眼フルカラー MicroLED、回折導波路、FOV 30°、約 3,500 nit（ピーク 6,000）。実機の `wm size` は 1280×480、`wm density` は 160（1dp = 1px）。Unity の確認用 APK でも `Screen` は 1280×480 で、左右に同じ3本の線が見えた
- 論理画面は1枚。素の Android UI は左右に割れるので、640×480 を描いて両眼へ複製する。密度 160 なので、dp で組んでも物理ピクセルと一致する
- カメラは2台、どちらも背面。静止画は `device@3.7/legacy/0`（4032×3024、焦点距離 1.90mm）を1枚だけ開く。`legacy/1`（640×480、焦点距離 0.71mm）は空間用の広角で、`WebCamTexture` では開かない。確認用 APK で `Camera.open(0)` のワンショットが通り、4032×3024 の JPEG 2,358,138 バイトを取得した。グラスの `dev.shodoghost.glass` でも同じ手順で 2,551,170 バイトを取り、3 本の線が見えた。EXIF は焦点距離 1.90mm、35mm 換算 11mm、F2.25、メーカー RayNeo、モデル ARGF20。魚眼ではない
- 3マイク、テンプルの前後上下スワイプとタップ、245mAh、Wi-Fi 6、Bluetooth 5.2 / 5.3（資料によって表記が分かれる）

製品ページは Bluetooth 5.3、デバイス紹介表は 5.2 です。この契約が使うのは MTU 交換、Write、Notify までなので、どちらでも成立します。
