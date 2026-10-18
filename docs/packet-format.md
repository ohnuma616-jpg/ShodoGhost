# パケット

リトルエンディアン。実装は `FrameCodec`、`MessageCodec`、`Protocol.encode`、`InboundAssembler`。

## フレーム

| オフセット | サイズ | フィールド |
| --- | --- | --- |
| 0 | 1 | SOF `0xA5` |
| 1 | 1 | バージョン `0x01` |
| 2 | 1 | `MessageType` |
| 3 | 1 | フラグ |
| 4 | 2 | シーケンス（uint16、ラップする） |
| 6 | 2 | ペイロード長 |
| 8 | N | ペイロード |
| 8+N | 2 | CRC-16/CCITT-FALSE（SOF からペイロード末尾まで。CRC 自身は含めない） |

フラグ:

| ビット | 名前 | 意味 |
| --- | --- | --- |
| 0 | `ACK_REQUEST` | 受信側は `ACK` を返す |
| 1 | `FIN` | このメッセージの最後。単発フレームでも立てる |
| 2 | `ERROR` | `ERROR` メッセージ |
| 3 | `FRAGMENT` | ペイロードの先頭 4byte が分割ヘッダ |

CRC の確認値: ASCII `123456789` の CRC-16/CCITT-FALSE は `0x29B1`。JPEG の CRC-32（IEEE）は同じ入力で `0xCBF43926`。

## 分割

1方向につき再構成中のメッセージは1つ。ペイロードが `FrameCodec.maxPayload(mtu)` を超える筆画パックだけ分割する。

分割ペイロード:

| オフセット | サイズ | フィールド |
| --- | --- | --- |
| 0 | 1 | フラグメント番号（0 始まり） |
| 1 | 1 | 個数 |
| 2 | 2 | msgId（先頭フレームのシーケンス） |
| 4 | 残り | 本体の断片 |

`FIN` は最後の番号だけ。`ACK_REQUEST` は先頭だけ。到着順は問わない。`InboundAssembler` が番号で結合する。

## 座標

用紙も HUD も 0..10000 の整数。用紙の原点は左上。HUD の原点は 640×480 キャンバスの左上。

ピクセル化は `HudCanvas.normToPxX` / `normToPxY`。10000 が 639 と 479 に対応する。

四隅の順は左上、右上、右下、左下。`Homography.fromPaperQuad` が用紙の四隅 `(0,0)-(10000,0)-(10000,10000)-(0,10000)` をその HUD 四隅へ写す。

## メッセージ一覧

制御メッセージのサイズはテスト `controlMessagesFitInMinimumMtuAndRoundTrip` が下限 MTU で固定している。

### スマホ → グラス

| 型 | コード | ペイロード |
| --- | --- | --- |
| `HELLO` | `0x01` | version u8、要求 MTU u16、role u8、nonce 8 |
| `SET_MODE` | `0x02` | mode u8、paper u8 |
| `EXEMPLAR_META` | `0x03` | id u32、strokeCount u16、pointCount u16、layerMask u8 |
| `CALIBRATE_START` | `0x04` | method u8、timeoutSec u8 |
| `CALIBRATE_COMMIT` | `0x05` | method u8、四隅 8×i16、widthMm i16、heightMm i16 |
| `CAPTURE_NOW` | `0x06` | reason u8、jpegQuality u8（1..100） |
| `SHOW_ADVICE` | `0x07` | 行数 u8、各行は長さ u8 + UTF-8。最大2行、各42byte |
| `CLEAR_OVERLAY` | `0x08` | mask u8。ビットは `OverlayBits`。`0xFF` は全部 |
| `BEGIN_WIFI_SESSION` | `0x09` | IPv4 4、port u16、token 16、purpose u8 |
| `END_WIFI_SESSION` | `0x0A` | reason u8 |
| `PING` | `0x0B` | cookie u32 |
| `SET_HUD` | `0x0C` | brightness u8、lineWidth u8（2..8）、crosshair u8 |
| `SLEEP_HINT` | `0x0D` | displayOffDelayMs u16 |
| `STROKE_PACK` | `0x20` | 下記 |
| `TOUCH`（リモコン） | `0x83` | 下記 `TOUCH` と同じ |

role は `PHONE_CENTRAL = 1`、`GLASS_PERIPHERAL = 2`。

mode は `IDLE=0`、`TRACE=1`、`SIDE_BY_SIDE=2`、`REVIEW=3`。

paper は `HANSHI=0`（242×333mm）、`NOSHI=1`（175×85）、`ICHIJI=2`（135×165）。mm は `CALIBRATE_COMMIT` が優先。

method は `ONE_SHOT=1`、`MANUAL=2`、`SLAM=3`。既定のスマホは 1 を送る。

reason は `TEMPLE=1`、`PHONE=2`、`PTT=3`。

purpose は `UPLOAD_JPEG=1`、`UPLOAD_AND_RESULT=2`。待ち時間にラジオを握らないため、通常は 1。

### グラス → スマホ

| 型 | コード | ペイロード |
| --- | --- | --- |
| `HELLO_ACK` | `0x81` | version、accept u8、battery% u8、thermal u8、mV u16、hwFlags u16、nonce 8 |
| `STATE` | `0x82` | mode、linkPhase、thermal、battery%、camera、overlayBits、mV u16 |
| `TOUCH` | `0x83` | action u8、elapsedMs u32 |
| `CAPTURE_READY` | `0x84` | jpegBytes u32、crc32 u32、cameraReleased u8（1 のみ）、width u16、height u16 |
| `ERROR` | `0x85` | code u16、長さ u8、UTF-8 最大48byte |
| `PONG` | `0x86` | cookie u32、uptimeSec u32 |
| `ACK` | `0x87` | ackSeq u16、type u8、status u8（0 ok、1 rejected、2 busy） |
| `WIFI_READY` | `0x88` | ok u8 |
| `WIFI_CLOSED` | `0x89` | reason u8 |
| `SESSION_INFO` | `0x8A` | version、hwFlags u16、battery%、thermal、linkPhase |
| `CORRECTION_PACK` | `0x21` | 筆画パック。`correction = true` |

thermal は `OK=0`、`WARM=1`、`HOT=2`。`HOT` のときグラスは新規の `CAPTURE_NOW` を `BUSY` または `ERROR/THERMAL` で拒む。

camera は `CLOSED=0`、`OPENING=1`、`OPEN=2`。バースト以外は `CLOSED`。

hwFlags: bit0 メインカメラ、bit1 Wi-Fi、bit2 SLAM アダプタがコンパイルされている、bit3 VGA 空間カメラ。

linkPhase のコードは `LinkPhase` と一致する（`IDLE=0` … `BACKOFF=8`）。

### 筆画パック

`STROKE_PACK` と `CORRECTION_PACK` の本体は同じ。

| フィールド | 型 |
| --- | --- |
| id | u32。手本 id または添削 id |
| packIndex | u16 |
| packCount | u16 |
| strokeCount | u16。1..32 |
| 繰り返し | layer u8、widthPx u8、点数 u16、点ごとに x i16、y i16 |

layer は `SKELETON=1`、`CONTOUR=2`、`CORRECTION=3`、`CROSSHAIR=4`、`MARKER=5`。

幅は 2..8。点数は 1..400。座標は 0..10000。

## エラーコード

| コード | 名前 |
| --- | --- |
| 1 | `PROTOCOL_MISMATCH` |
| 2 | `CRC_FAIL` |
| 3 | `BUSY` |
| 4 | `THERMAL` |
| 5 | `CAMERA_BUSY` |
| 6 | `WIFI_TIMEOUT` |
| 7 | `TOKEN_REJECTED` |
| 8 | `PAYLOAD_TOO_LARGE` |
| 9 | `UNSUPPORTED_MODE` |
| 10 | `NOT_CALIBRATED` |
| 11 | `MTU_TOO_SMALL` |
| 12 | `BAD_FRAME` |
| 13 | `FRAGMENT` |

## Wi-Fi テキスト

WebSocket のパスは `/v1/session`、サブプロトコルは `shodo-ghost.v1`。

テキストは JSON にしない。`WifiText` の `key=value` 改行形式。

```text
op=jpeg_begin
bytes=120000
width=4000
height=3000
crc32=3421786406
```

`op` は `hello`、`hello_ack`、`jpeg_begin`、`jpeg_end`、`result`、`bye`、`error`。

`jpeg_begin` の直後のバイナリフレームが JPEG 本体で、1フレーム最大 16KiB。`jpeg_end` で閉じる。トークンは `hello` の `token` フィールド（16byte を16進）。不一致はソケットを切り `TOKEN_REJECTED`。

ハードタイムアウトは 20秒。JPEG は 4MiB まで。
