# 参照プロジェクト

過去の RayNeo 向け作業ツリー。Shodo Ghost のソースには取り込まない。公開クローンを読んで、今の契約と突き合わせた。

| 名前 | URL | 中身 |
| --- | --- | --- |
| FixLensAR | https://github.com/ohnuma616-jpg/FixLensAR | Unity 2022.3 のプラモデル手順 HUD。眼鏡単体。iPhone は無い |
| TapInsight-unipanel | https://github.com/ohnuma616-jpg/TapInsight-unipanel | グラス上の Android アプリ。ブラウザ、音声、Gemini。設定はグラスが配る Web ページ |

## 契約に取り込む事実

物理フレームバッファは 1280×480 で、論理画面は片眼 640×480。同じ絵を左右へ出す。

- FixLensAR の `Assets/Scripts/BinocularSbsUi.cs` は、Canvas を左半分と右半分に分け、左の3段 UI を右へ複製する。
- TapInsight の `tapbrowser/.../BinocularSbsLayout.kt` は、子を1つだけ受け、物理幅の半分で測ってから左右へ2回描く。Mercury SDK の `com.ffalcon.mercury.android.sdk.ui.wiget.MirroringView` が使えるときはそちらへ委譲する。クラス名の `wiget` は SDK 側の綴り。

Shodo Ghost の `renderHud` が 640×480 を1枚作り、`DeskMirrorPresenter` が左右へコピーする形は、この2つと同じ分担である。1280 幅のレイアウトをアプリが直接組む必要はない。

FixLensAR の仕様（`docs/spec.md`、2026-10-13）で実機に寄せて書いてあること:

- 黒 `#000000` は透明。
- `WebCamTexture` で取れたのは白黒の魚眼で、IMX681 の RGB は未確定。OpenXR と ShareCamera は使っていない。
- `SpeechRecognizer` はウェイクワード専用で、手順の音声コマンドには使えなかった。オンデバイスの Vosk を使っている。
- ジャイロ積分の枠はドリフトし、平行移動には追従しない。空間固定は別 APK で、現行ビルドには OpenXR を入れない。

テンプルの意味は TapInsight の README と揃っている。シングルタップは決定、ダブルタップは戻る、左右スワイプは移動、上下スワイプはスクロール。Shodo Ghost では前後が手本、上下が助言ページ。

## 契約を変えないこと

- 色。FixLensAR の本文は緑 `#00FF66` 中心。Shodo Ghost の線は骨格シアン、外形緑、添削赤、文字白のまま。
- カメラ。常時 `WebCamTexture` や TapInsight のカメラ付きフォアグラウンドサービスにはしない。撮影は1枚で閉じる。
- 音声。グラスに認識エンジンを常駐させない。Push-to-Talk のまま。
- コンパニオン。FixLensAR は眼鏡単体、TapInsight はグラス自身が設定ページを配る。Shodo Ghost のコンパニオンは iPhone。
- 推論。Gemini や手順 JSON の解釈はグラスで回さない。添削は iPhone 側。
- Android の版表記。TapInsight のガイドは Android 14 と書いている。Shodo Ghost の契約は RayNeo AIOS（Android 12）のまま。実機の `ro.build.version.sdk` が取れるまで数字は動かさない。
