# ディレクトリ

Phase 1 でリポジトリにあるもの:

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
    src/test/kotlin/dev/shodoghost/protocol/ProtocolTest.kt
```

Phase 1 で加えてあるもの:

```text
ios/Protocol/          Swift Package。iPhone がリンクする符号器
fixtures/canonical.txt Kotlin が書いたバイト列。Swift が再符号化して一致を確認する
```

後続フェーズで足す予定。今は作らない。

```text
glass/          Phase 3  Kotlin Android。View と Canvas。MercurySDK は adapter 実装だけが参照
ios/App/        Phase 4  SwiftUI
server/         Phase 4 の任意。Python、OpenCV、VLM。iPhone からのみ呼ばれる
```

`:protocol` は Android プラグインを適用しない。Phase 3 のグラスが `api(project(":protocol"))` する。iPhone は `ios/Protocol` を Swift Package としてリンクする。中身を Kotlin Multiplatform にはしない。iOS 向けの Kotlin/Native はこの Linux 環境で検証できないため、Swift で書いてバイト列を固定する。

## フェーズ境界

| フェーズ | 状態 | 中身 |
| --- | --- | --- |
| 1 | ここ | 契約、シーケンス、見直し、Kotlin と Swift の符号器 |
| 2 | 未着手 | グラスの GATT サーバと iPhone の CoreBluetooth。ループバックでモック検証 |
| 3 | 未着手 | 透過 HUD、セーフエリア、タッチを `TouchAction` へ |
| 4 | 未着手 | SwiftUI、前面の練習画面での待受、`AnalysisBackend` |
| 5 | 未着手 | スリープ復帰の実機調整、再接続、計測に基づく省電力 |

Phase 2 は新しいパケットを増やさない。グラスは Kotlin の `Protocol.encode`、iPhone は Swift の `Protocol.encode` を Characteristic の書き込みと Notify に接続する。
