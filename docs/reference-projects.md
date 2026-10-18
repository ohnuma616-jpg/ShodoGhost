# 参照プロジェクト

過去の RayNeo 向け作業ツリー。Shodo Ghost のソースには取り込まない。公開クローンを読んで、今の契約と突き合わせた。

| 名前 | URL | 中身 |
| --- | --- | --- |
| FixLensAR | https://github.com/ohnuma616-jpg/FixLensAR | Unity 2022.3 のプラモデル手順 HUD。眼鏡単体。iPhone は無い |
| TapInsight-unipanel | https://github.com/ohnuma616-jpg/TapInsight-unipanel | グラス上の Android アプリ。ブラウザ、音声、Gemini。設定はグラスが配る Web ページ |

## 契約に取り込む事実

物理フレームバッファは 1280×480 で、論理画面は片眼 640×480。同じ絵を左右へ出す。接続した X3 Pro の `wm size` は 1280×480、`wm density` は 160 だった。Unity 2022.3.62f3 の確認用 APK `dev.shodoghost.probe` でも `ShodoProbe 1280x480` と出て、シアン、緑、赤の3本が見えた。

- FixLensAR の `Assets/Scripts/BinocularSbsUi.cs` は、Canvas を左半分と右半分に分け、左の3段 UI を右へ複製する。
- TapInsight の `tapbrowser/.../BinocularSbsLayout.kt` は、子を1つだけ受け、物理幅の半分で測ってから左右へ2回描く。Mercury SDK の `com.ffalcon.mercury.android.sdk.ui.wiget.MirroringView` が使えるときはそちらへ委譲する。クラス名の `wiget` は SDK 側の綴り。

Shodo Ghost の `renderHud` が 640×480 を1枚作り、`DeskMirrorPresenter` が左右へコピーする形は、この2つと同じ分担である。1280 幅のレイアウトをアプリが直接組む必要はない。

FixLensAR の仕様（`docs/spec.md`、2026-10-13）で実機に寄せて書いてあること:

- 黒 `#000000` は透明。
- `WebCamTexture` で取れたのは白黒の魚眼。接続した X3 Pro では、カメラ ID `0`（`device@3.7/legacy/0`）が 4032×3024、焦点距離 1.90mm。ID `1`（`legacy/1`）が 640×480、焦点距離 0.71mm。どちらも背面で、魚眼は ID `1` と一致する。OpenXR と ShareCamera は使っていない。
- 同じ確認用 APK で、ID `0` のワンショットが実写になった。`android.hardware.Camera.open(0)`、絵のサイズ 4032×3024、`SurfaceTexture` でプレビューを始め、約 0.7 秒後に `takePicture`。Java の `PictureCallback` が 2,358,138 バイトを書き、`stopPreview` と `release` を呼んだ。EXIF は 2026-10-19 02:02:49 +09:00、焦点距離 1.90mm、35mm 換算 11mm、F2.25、ISO 546、露光 1/100 秒。メーカー RayNeo、モデル ARGF20。机の前のカラー写真で、魚眼ではない。
- Unity の `AndroidJavaProxy` では Camera2 の `CameraDevice.StateCallback` を実装できない。抽象クラスであり、`IllegalArgumentException` になった。C# 側の `Camera.PictureCallback` も `onPictureTaken` が届かず、Java 側のコールバックで書けた。
- その JPEG の保存先は Unity の `persistentDataPath`（`/storage/emulated/0/Android/data/dev.shodoghost.probe/files/still.jpg`）。`run-as` からは `Permission denied`。`adb pull` では同じパスを取れた。
- `dumpsys media.camera` では API1 から見えるカメラは 2 台。`dev.shodoghost.probe` の PID 7377 は 02:02:47 に device 0 へ接続し、02:02:49 に切断した。それ以降の接続は無く、HAL の `device@3.7/legacy/0` に掴んでいるクライアントは残っていない。device 1 は開いていない。
- `SpeechRecognizer` はウェイクワード専用で、手順の音声コマンドには使えなかった。オンデバイスの Vosk を使っている。
- ジャイロ積分の枠はドリフトし、平行移動には追従しない。空間固定は別 APK で、現行ビルドには OpenXR を入れない。

テンプルの意味は TapInsight の README と揃っている。シングルタップは決定、ダブルタップは戻る、左右スワイプは移動、上下スワイプはスクロール。Shodo Ghost では前後が手本、上下が助言ページ。

## 契約を変えないこと

- 色。FixLensAR の本文は緑 `#00FF66` 中心。Shodo Ghost の線は骨格シアン、外形緑、添削赤、文字白のまま。
- カメラ。常時 `WebCamTexture` や TapInsight のカメラ付きフォアグラウンドサービスにはしない。撮影は1枚で閉じる。
- 音声。グラスに認識エンジンを常駐させない。Push-to-Talk のまま。
- コンパニオン。FixLensAR は眼鏡単体、TapInsight はグラス自身が設定ページを配る。Shodo Ghost のコンパニオンは iPhone。
- 推論。Gemini や手順 JSON の解釈はグラスで回さない。添削は iPhone 側。
- Android の版表記。TapInsight のガイドは Android 14 と書いている。接続した X3 Pro の `ro.build.version.sdk` は 32（Android 12L）だった。契約は API 32 まで。Android 14 には上げない。
