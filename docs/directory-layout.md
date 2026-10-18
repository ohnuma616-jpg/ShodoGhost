# ディレクトリ

Phase 9 まででリポジトリにあるもの:

```text
shodo-ghost/
  README.md
  settings.gradle.kts
  build.gradle.kts
  gradle.properties
  docs/
    architecture.md
    spec-review.md
    ble-gatt.md
    packet-format.md
    sequences.md
    hud-and-input.md
    iphone.md
    directory-layout.md
    phase-2.md
    phase-3.md
    phase-4.md
    phase-5.md
    reference-projects.md
  scripts/
    loopback-check.sh      TCP のグラス役と Swift の往復試験
    ci-linux.sh            Ubuntu の契約テスト
    ci-setup-linux.sh      JDK 21 と Swift 6.2
  .buildkite/
    pipeline.yml           Buildkite の Mac で iPhone をコンパイル
  .depot/workflows/
    checks.yml             Depot の Ubuntu で契約テスト
  docs/cloud-ci.md         接続手順
  protocol/
    build.gradle.kts
    src/main/kotlin/dev/shodoghost/protocol/
      Limits.kt            数値、余白以外の上限、HwFlags
      Hud.kt               640×480、色、用紙、筆画
      Homography.kt        四隅から HUD への射影
      Enums.kt             メッセージ種別、タッチ、リンク相
      GattProfile.kt       UUID とプロパティ
      WifiSession.kt       WebSocket のパスとテキストフレーム
      FrameCodec.kt        SOF フレームと CRC
      Messages.kt          メッセージ本体
      Assembler.kt         分割と再構成
      LinkStateMachine.kt  BLE 常時 / Wi-Fi バースト
      PowerPolicy.kt       相ごとに持ってよいラジオ。ディスクへは残さない
      Adapters.kt          SDK 境界と十字リーダー
      camera/LegacyStillCapture.kt  Camera.open(0)、1枚、release
      camera/GlassStillShot.kt      メモリ1枚と CAPTURE_READY
      Crc.kt
      ByteIo.kt
      link/GlassSession.kt GATT サーバが委譲するセッション
      link/AttFraming.kt   テスト用のモックパイプ。オンエアではない
      loopback/LoopbackGlassHost.kt
      hud/HudBitmap.kt     640×480、文字セーフと光学クリップ
      hud/HudPaint.kt      太線と二値の文字
      hud/HudRenderer.kt   モード別の配置、左右複製
      hud/PracticeHud.kt   TouchAction から練習状態へ
      hud/BringUpHud.kt    確認用のシアン、緑、赤
    src/test/kotlin/dev/shodoghost/protocol/
      ProtocolTest.kt
      link/GlassSessionTest.kt
      hud/HudRendererTest.kt
      camera/LegacyStillCaptureTest.kt
      camera/GlassStillShotTest.kt
```

iPhone 側:

```text
ios/App/               Xcode アプリ。撮影ボタンが CAPTURE_NOW を送る
ios/Protocol/          Swift Package。符号器、PhoneSession、練習のバースト
  Sources/.../PhoneSession.swift
  Sources/.../CoreBluetoothPhoneRadio.swift   iOS だけでコンパイル
  Sources/.../TcpPhoneRadio.swift             BLE ループバック用
  Sources/.../WifiBurst.swift                 JPEG の WebSocket。Linux では TCP
  Sources/.../PracticeCoordinator.swift       前面ゲートと添削の順
  Sources/.../PracticeScreen.swift            SwiftUI。Linux では空
fixtures/canonical.txt Kotlin が書いたバイト列。Swift が再符号化して一致を確認する
```

Android SDK があるときだけ `:glass` を含める。

```text
glass/
  build.gradle.kts
  src/main/AndroidManifest.xml
  src/main/kotlin/dev/shodoghost/glass/
    GlassActivity.kt       左右の画面。撮影は CAPTURE_NOW
    GlassLink.kt           GATT ペリフェラル。広告名 ShodoGhost
    HudView.kt             1280×480 を補間なしで出す
    AndroidHudGlyphs.kt    システムフォントで文字を二値化
    AndroidStillCamera.kt  Camera.open(0)
```

後続で足す予定。今は作らない。

```text
MercurySDK          adapter の裏。BaseMirrorActivity はまだ使わない
server/             任意。Python、OpenCV、VLM。iPhone からのみ呼ばれる
```

`:protocol` は Android プラグインを適用しない。実機のグラスが `api(project(":protocol"))` する。iPhone は `ios/Protocol` を Swift Package としてリンクする。中身を Kotlin Multiplatform にはしない。iOS 向けの Kotlin/Native はこの Linux 環境で検証できないため、Swift で書いてバイト列を固定する。

## フェーズ境界

| フェーズ | 状態 | 中身 |
| --- | --- | --- |
| 1 | 済み | 契約、シーケンス、見直し、Kotlin と Swift の符号器 |
| 2 | 済み | `GlassSession` と `PhoneSession`。TCP ループバックで往復。Android GATT モジュールは未作成 |
| 3 | 済み | 640×480 の HUD と `PracticeHud`。Android の Activity は未作成 |
| 4 | 済み | 前面の練習画面、JPEG バースト、`ManualCritique`。`NWListener` は未作成 |
| 5 | 済み | スリープで Wi-Fi を放す。再接続で JPEG 1 枚と手本を戻す。mAh は未計測 |
| 6 | 済み | カメラ 0 を1枚だけ開き、閉じてから `CAPTURE_READY` |
| 7 | 済み | 1280×480 の Activity。起動 4 秒後に 1 枚撮って閉じる |
| 8 | 済み | `ShodoGhost` を広告し、`CAPTURE_NOW` で 1 枚撮る |
| 9 | ここ | `ios/App`。接続して撮影ボタンが `CAPTURE_NOW` を送る。コンパイルは Buildkite の Mac |

Phase 2 は新しいパケットを増やしていない。グラスは Kotlin の `Protocol.encode`、iPhone は Swift の `Protocol.encode` を Characteristic の書き込みと Notify に接続する。
