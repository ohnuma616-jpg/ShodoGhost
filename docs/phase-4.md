# Phase 4 — 練習画面と JPEG バースト

前面の練習画面だけが JPEG を受け、Wi-Fi を閉じてから添削を BLE で返す。新しいオンエアのパケットは無い。順番は [sequences.md](sequences.md) の「書き終わりと添削」と同じ。

この環境で動くのは Swift パッケージの中まで。`PracticeScreen` は `canImport(SwiftUI)` のときだけコンパイルされる。Xcode のアプリターゲットと `NWListener` の本体は置いていない。Linux の待受は `TcpWifiListener`。Android の `glass/` も Python の `server/` も作っていない。

## 前面ゲート

`PracticeCoordinator` が `PhoneSession`、`AnalysisBackend`、`WifiBurstLink` を持つ。セッションの delegate にはならない。`CAPTURE_READY` はアプリが `noteCaptureReady` へ渡す。

| `foreground` | すること |
| --- | --- |
| false | `CAPTURE_READY` を1件覚える。待受は開かない。`BEGIN_WIFI` も送らない |
| true | 覚えていた1件があれば、その場でバーストする |
| true から false | `link.stop()`。進行中のソケットを閉じる |

`PracticeScreen` はタイトル「半紙をなぞる」。ボタンは「書き終わり」（`TouchAction.longPress`）と「画面の四隅を送る」（`CALIBRATE_COMMIT`、method は manual）。`onAppear` / `onDisappear` が親のクロージャを通して `setForeground` を切り替える。助言の文字列は親が `lastCritique` を読んで渡す。

## バーストの順

1. `SHOW_ADVICE`「添削中」
2. `listen(port: 0)`。実ポートは `boundPort`
3. `BEGIN_WIFI_SESSION`。IPv4 の既定は `172.20.10.1`。purpose は `UPLOAD_JPEG`
4. JPEG を受ける。上限は 20 秒、4MiB、1フレーム 16KiB
5. 待受を止める
6. `END_WIFI_SESSION`。ACK のあと相は `BLE_READY`
7. `AnalysisBackend.critique`
8. `SHOW_ADVICE` と `CORRECTION_PACK`

失敗したときは待受を止め、相が `WIFI_WAKE` / `TRANSFER` / `WIFI_TEARDOWN` なら `END_WIFI` を送り、`CLEAR_OVERLAY` で助言ビットを消してからエラーを返す。グラスは「添削中」のまま残らない。

`sendCorrections` は筆画を 32 本ずつ `CORRECTION_PACK` に分け、Payload へ書く。

## WebSocket

パスは `/v1/session`。サブプロトコルは `shodo-ghost.v1`。Version 13。Accept は `base64(SHA1(key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"))`。RFC の例 `dGhlIHNhbXBsZSBub25jZQ==` は `s3pPLMBiTxaQ9kYGzzhZRbK+xOo=`。

テキストは key=value。JSON ではない。クライアントのフレームはマスクする。サーバのフレームはマスクしない。

| op | 向き | 中身 |
| --- | --- | --- |
| hello | グラス → iPhone | `token` は 16 バイトの十六進 |
| hello_ack | iPhone → グラス | `ok=1` |
| error | iPhone → グラス | トークン不一致は code 7（`TOKEN_REJECTED`）で閉じて終わる |
| jpeg_begin | グラス → iPhone | `bytes`、`width`、`height`、`crc32`（十進） |
| バイナリ | グラス → iPhone | 直後に JPEG。各フレーム 16KiB 以下 |
| jpeg_end | グラス → iPhone | バイト数と CRC が `CAPTURE_READY` と一致したら受ける |
| bye | iPhone → グラス | 検証のあと。添削結果は載せない |

`result` はこの purpose では使わない。添削の待ち時間に無線を握らない。

`TcpWifiListener` は `127.0.0.1`、`SO_REUSEADDR`、待ち行列 1。ポート 0 は `getsockname` の値を使う。実機の iPhone では同じ `WifiBurstLink` の向こうを `NWListener` にする。

Swift では CRLF が 1 つの `Character` になる。ヘッダ行は改行文字そのもので割る。`split(separator: "\n")` では行に分かれない。

## 添削の代替

`ManualCritique` は Python が無いときの `AnalysisBackend`。四隅は見ない。赤の一画 `(2000,2000)-(8000,8000)`、幅 2px と、助言「起筆を垂直に」「ハネを短く」を返す。

## 動かし方

```bash
swift test --package-path ios/Protocol
```

見るのは、RFC の Accept、CRLF ヘッダのキー、背面では `BEGIN_WIFI` を出さないこと、トークン拒否で助言を消して `BLE_READY` に戻ること、TCP の JPEG のあと `END_WIFI` が `CORRECTION_PACK` より前にあること。

## まだ置かない

スリープ復帰、再接続の実機調整、計測に基づく省電力は Phase 5。`NWListener`、Xcode プロジェクト、OpenCV のサーバは、実機か LAN が揃ってから。
