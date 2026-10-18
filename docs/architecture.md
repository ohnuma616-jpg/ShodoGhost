# アーキテクチャ

## 採用スタック

| 場所 | 採用 | 理由 |
| --- | --- | --- |
| グラス側契約 | Kotlin / JVM、`:protocol` | グラスアプリがリンクする。Android 依存は入れない |
| グラス | Kotlin、Android View、640×480 の Canvas | 公式のシステムアプリは 0DoF。Unity の常時 SLAM は 245mAh とカメラ排他に合わない |
| iPhone 側契約 | Swift 6、`ios/Protocol` | CoreBluetooth からそのまま呼ぶ。バイト列は `fixtures/canonical.txt` で Kotlin と一致させる |
| iPhone アプリ | SwiftUI、iOS 17 以降。Phase 4 | セントラル、設定、添削。詳細は [iphone.md](iphone.md) |
| 添削 | iPhone 上の `AnalysisBackend` | 重い処理はグラスの外。Python（OpenCV + VLM）はその実装の一つで、グラスの通信相手にはしない |

スマホは iPhone です。バックグラウンドの BLE は CoreBluetooth。練習中だけの JPEG 待受は、実機では Network.framework の `NWListener`、この環境の検証では同じ `WifiBurstLink` の `TcpWifiListener` です。

接続した X3 Pro（ARGF20 / MercuryLiteXR）は API 32（Android 12L）だった。minSdk は 31 のまま、ターゲットは 32。API 33 以降は使わない。GMS は使わない。依存禁止は `play-services-*`、`firebase-*`、Maps SDK、Fused Location Provider、広告 SDK。

## 役割

```text
テンプル / 音声PTT          iPhone（前面の練習画面）         任意の LAN
        |                         |                              |
        v                         v                              v
  グラス (Kotlin HUD)  --BLE-->  SwiftUI アプリ  --任意-->  Python 添削
        ^                         |
        +---- Wi-Fi 短時間 -------+
              JPEG だけ。アプリが前面のとき
```

グラスがやること:

- 640×480 の HUD を両眼へ同じ絵として出す
- テンプル操作を `TouchAction` にする
- 指示があったときだけ IMX681 を開き、JPEG を1枚取って閉じる
- BLE で状態を通知し、Wi-Fi バーストの間だけ WakeLock を持つ

iPhone がやること:

- CoreBluetooth セントラル、再接続、バックオフ。バックグラウンドは `bluetooth-central` の状態復元
- 練習画面が前面のあいだだけ `NWListener` で JPEG を受ける
- 手本ライブラリ、用紙プロファイル、アカウント、文字入力
- JPEG を受け、`AnalysisBackend` に渡す
- 添削のポリラインと2行の文をグラスへ返す

Python がやること（任意）:

- 半紙の台形補正、二値化、細線化
- 骨格・太さ・起筆 / トメ / ハネの幾何差分
- VLM による2行の助言

Python が止まっていても、`ManualCritique` が赤の一画と定型の2行を返す。四隅の検出はしない。詳細は [phase-4.md](phase-4.md)。

## 既定は 0DoF

半紙への位置合わせは、白紙（または枠の見える紙）をワンショット撮影し、スマホ側で四隅を出して `CALIBRATE_COMMIT` で戻す。グラスは4点から `Homography` を作り、用紙座標 0..10000 の筆画を HUD に乗せる。

頭が動けば 0DoF の線は半紙からずれる。そのときはタップで再キャリブレーションする。連続 SLAM では追従しない。

`PlaneAnchorAdapter.available` が true のビルドだけ、明示セッションで VGA 空間カメラの 6DoF を使ってよい。公式資料では SLAM 有効中のカメラ取得は ShareCamera 経路になる。静止画の前に SLAM を止めるか ShareCamera を使う判断は、そのアダプタ実装の責任にする。既定ビルドの `available` は false。

サイド並置モードは、ロックが無いあいだ HUD 上部の線として出す。平面アンカーが取れたセッションだけ、同じ筆画をアンカーへ渡す。

## カメラとマイク

- 書き終わりは自動検出しない。テンプル長押し、スマホのリモコン、Push-to-Talk のいずれか
- Wake Word の常駐はしない。マイクを開くのは PTT のあいだだけ
- プレビュー Surface を出し続けない。`CameraCaptureAdapter.captureStill` は戻る前にカメラを閉じる
- `CaptureReady.cameraReleased` が false のメッセージは作れない

## スリープ

画面消灯で Wi-Fi ソケットは OS に切られる。切る前提で状態機械を組んである。保持してよいラジオは [phase-5.md](phase-5.md) の `PowerPolicy`。実機の mAh は未計測。

1. `BLE_READY` では Wi-Fi ラジオを要求しない
2. JPEG が必要なとき `BEGIN_WIFI`。効果は WakeLock と Wi-Fi Lock の取得、そのあとソケット
3. 転送が終わるか、20秒のハードタイムアウトでソケットを閉じ、ロックを外し、`BLE_READY` へ戻る
4. BLE 自体が死んだら `BACKOFF`。再試行間隔は 1s、2s、4s、8s、以降 15s（`RetrySchedule`）

添削の待ち時間に Wi-Fi を握らない。JPEG を送り切ったらすぐ閉じ、結果は BLE の `SHOW_ADVICE` と `CORRECTION_PACK` で返す。HUD には先に「添削中」を出してよい。

## 未接続

- 最後の手本はグラスのメモリに残す。プロセスが死んだらスマホが次の `STATE` を見て、手本ビットが無いとき再送する
- 未接続は警告色の2px記号。文字で「未接続」と出してよい（セーフエリア内、1行）
- 未送信 JPEG はメモリに1枚まで。再接続後に `CAPTURE_READY` を再送する。プロセスが死んだら消える。ディスクには書かない

## アダプタ

実装クラスは後続フェーズ。境界は `dev.shodoghost.protocol` の次の型。

| 型 | 実装する側 | SDK を隠すもの |
| --- | --- | --- |
| `BinocularPresenter` | グラス | `BaseMirrorActivity` / `BindingPair`。SDK が無い実行環境では左右半分へ同じ Bitmap を描く |
| `TempleInputSink` | グラス | `TempleAction`。デスク検証ではキー入力 |
| `CameraCaptureAdapter` | グラス | Camera2。ワンショット |
| `PlaneAnchorAdapter` | グラス | Unity または Mercury の SLAM。既定は未実装で `available = false` |
| `AnalysisBackend` | iPhone | モック、または LAN の Python |

`BinocularPresenter` は 640×480 を1枚受け取る。1280×480 のレイアウトをアプリが直接組むことは契約にない。

## セキュリティ

- iPhone は接続後にペアリングする。HELLO より前の Command 書き込みはグラスが捨てる。iOS は暗号化が必要な Characteristic へ最初に触れたとき、システム UI でペアリングを出す
- HELLO の 8byte nonce を ACK がエコーする
- Wi-Fi トークンはバーストごとに 16byte。使い切り
- ローカル WebSocket は平文。LAN の WPA に依存する。グラスに証明書 UI を出さないための判断
- JPEG は BLE に載せない

## GMS を使わない位置づけ

位置情報はこのアプリの機能に入っていない。将来入れる場合も取得はスマホ側で、結果の数値だけをグラスへ渡す。RayNeo IPC SDK の GPS ストリームはシステム連携用なので、このアプリの契約には入れない。
