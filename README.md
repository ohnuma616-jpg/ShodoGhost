# Shodo Ghost AR

RayNeo X3 / X3 Pro 向けの美文字・毛筆練習アシスタント。半紙の上に手本の線を重ね、書き終えた静止画をスマートフォン側で添削する。

今あるのは **Phase 5（スリープ、再接続、ラジオの保持）** までです。640×480 の透過 HUD と、前面だけの JPEG バーストに加え、パネル消灯では Wi-Fi だけを放します。未送信の JPEG はメモリ 1 枚を再接続後に送り直します。ディスクへは書きません。SwiftUI の画面はこの Linux ではコンパイルせず、Xcode アプリと Android のグラスアプリはまだ無い。

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

## ハードウェア前提

RayNeo 公式資料（X3 Pro、2025年の開発者ドキュメント）に合わせています。

- Snapdragon AR1 Gen 1、RayNeo AIOS（Android 12）
- 両眼フルカラー MicroLED、回折導波路、640×480、FOV 30°、約 3,500 nit（ピーク 6,000）
- 論理画面は1枚。素の Android UI は左右に割れるので、640×480 を描いて両眼へ複製する
- メインカメラ Sony IMX681 12MP。空間位置用に別の VGA カメラがある
- 3マイク、テンプルの前後上下スワイプとタップ、245mAh、Wi-Fi 6、Bluetooth 5.2 / 5.3（資料によって表記が分かれる）

製品ページは Bluetooth 5.3、デバイス紹介表は 5.2 です。この契約が使うのは MTU 交換、Write、Notify までなので、どちらでも成立します。
