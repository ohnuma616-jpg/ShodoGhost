# Phase 6 — カメラ 0 のワンショット

静止画は `LegacyStillCapture` が1回の呼び出しで終える。`Camera.open(0)`、対応サイズにあれば 4032×3024、プレビューを 700ms 置いてから1枚、`stopPreview`、`release`。戻るときはカメラが閉じている。ID 1 は開かない。失敗しても `release` まで進み、そのときは `CapturedStill` を返さない。

この手順は ARGF20 の確認用 APK で通った。JPEG は 4032×3024、2,358,138 バイト、焦点距離 1.90mm。`dumpsys media.camera` では 02:02:47 に接続し、02:02:49 に切断した。

`GlassStillShot.take` は `BLE_READY` でパネルが点いていて、熱が `HOT` でなく、カメラが `CLOSED` のときだけ開く。取れたバイト列はメモリに1枚だけ残し、`CAPTURE_READY` には長さと CRC だけを載せる。ディスクには書かない。転送が `TRANSFER` を完了してセッションがメタデータを捨てると、バイト列も捨てる。消灯や切断では残る。

呼び出しは `GlassSession` のロックの外で行う。`onCaptureRequested` の中では撮らない。

`glass/.../AndroidStillCamera.kt` が実機の `android.hardware.Camera` へこの手順を渡す。Android SDK が要るので、この環境の `:protocol` テストには入っていない。Activity と MercurySDK はまだ無い。

## 動かし方

```bash
./gradlew :protocol:test
```

見るのは、4032×3024 を優先すること、撮影失敗でも `release` すること、消灯と `HOT` と Wi-Fi 中は開かないこと、成功した1枚だけをメモリに残すこと。
