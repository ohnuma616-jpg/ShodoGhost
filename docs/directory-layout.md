# ディレクトリ

Phase 4 まででリポジトリにあるもの:

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
    reference-projects.md
  scripts/
    loopback-check.sh      TCP のグラス役と Swift の往復試験
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
      Adapters.kt          SDK 境界と十字リーダー
      Crc.kt
      ByteIo.kt
      link/GlassSession.kt GATT サーバが委譲するセッション
      link/AttFraming.kt   テスト用のモックパイプ。オンエアではない
      loopback/LoopbackGlassHost.kt
      hud/HudBitmap.kt     640×480、文字セーフと光学クリップ
      hud/HudPaint.kt      太線と二値の文字
      hud/HudRenderer.kt   モード別の配置、左右複製
      hud/PracticeHud.kt   TouchAction から練習状態へ
    src/test/kotlin/dev/shodoghost/protocol/
      ProtocolTest.kt
      link/GlassSessionTest.kt
      hud/HudRendererTest.kt
```

iPhone 側:

```text
ios/Protocol/          Swift Package。符号器、PhoneSession、練習のバースト
  Sources/.../PhoneSession.swift
  Sources/.../CoreBluetoothPhoneRadio.swift   iOS だけでコンパイル
  Sources/.../TcpPhoneRadio.swift             BLE ループバック用
  Sources/.../WifiBurst.swift                 JPEG の WebSocket。Linux では TCP
  Sources/.../PracticeCoordinator.swift       前面ゲートと添削の順
  Sources/.../PracticeScreen.swift            SwiftUI。Linux では空
fixtures/canonical.txt Kotlin が書いたバイト列。Swift が再符号化して一致を確認する
```

後続で足す予定。今は作らない。

```text
glass/          実機の Activity。SDK が届いてから。View と Canvas。MercurySDK は adapter 実装だけが参照
ios/App/        Xcode のアプリターゲット。画面本体は ios/Protocol の PracticeScreen
server/         任意。Python、OpenCV、VLM。iPhone からのみ呼ばれる
```

`:protocol` は Android プラグインを適用しない。実機のグラスが `api(project(":protocol"))` する。iPhone は `ios/Protocol` を Swift Package としてリンクする。中身を Kotlin Multiplatform にはしない。iOS 向けの Kotlin/Native はこの Linux 環境で検証できないため、Swift で書いてバイト列を固定する。

## フェーズ境界

| フェーズ | 状態 | 中身 |
| --- | --- | --- |
| 1 | 済み | 契約、シーケンス、見直し、Kotlin と Swift の符号器 |
| 2 | 済み | `GlassSession` と `PhoneSession`。TCP ループバックで往復。Android GATT モジュールは未作成 |
| 3 | 済み | 640×480 の HUD と `PracticeHud`。Android の Activity は未作成 |
| 4 | ここ | 前面の練習画面、JPEG バースト、`ManualCritique`。Xcode アプリと `NWListener` は未作成 |
| 5 | 未着手 | スリープ復帰の実機調整、再接続、計測に基づく省電力 |

Phase 2 は新しいパケットを増やしていない。グラスは Kotlin の `Protocol.encode`、iPhone は Swift の `Protocol.encode` を Characteristic の書き込みと Notify に接続する。
