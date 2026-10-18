# Phase 7 — グラスの画面

640×480 を `DeskMirrorPresenter` で 1280×480 に複製し、全面へ出す。起動から 4 秒後に `LegacyStillCapture` がカメラ 0 を 1 枚開き、`release` してから戻る。JPEG はアプリ非公開の `files/still.jpg` に 1 枚書く。これは引き出すための写しで、セッションの保留はこれまでどおりメモリだけ。

電話はまだ繋がない。`GlassStillShot` は `BLE_READY` のまま残し、この Activity からは呼ばない。

文字は `installHudGlyphs` で Android のシステムフォントに差し替える。線の色はシアン、緑、赤。撮影が終わると、下の行にバイト数が入る。

Android SDK が `ANDROID_HOME` か `local.properties` の `sdk.dir` にあるときだけ `:glass` をビルドに含める。無いときの `./gradlew :protocol:test` は今までどおり動く。

## 実機

リポジトリ直下に `local.properties` を置き、`sdk.dir` を Android SDK にする。Windows なら `sdk.dir=C\:\\Users\\ohnum\\AppData\\Local\\Android\\Sdk`。このファイルはコミットしない。

```bash
./gradlew :glass:assembleDebug
```

APK は `glass/build/outputs/apk/debug/glass-debug.apk`。パッケージは `dev.shodoghost.glass`。カメラ許可を渡してから起動し、15 秒待って `ShodoGlass` のログと `files/still.jpg` を見る。両眼に同じ 3 本が出て、`bytes` のあと `dumpsys media.camera` に device 0 の切断が残っていれば、この画面は足りている。

ARGF20 では 3 本が見えた。`files/still.jpg` は 2,551,170 バイト、4032×3024。EXIF は 2026-10-19 03:19:50 +09:00、焦点距離 1.90mm、35mm 換算 11mm、F2.25、ISO 728、露光 1/100 秒。メーカー RayNeo、モデル ARGF20。`dumpsys media.camera` では PID 8116 が 03:19:49 に device 0 へ接続し、03:19:51 に切断した。
