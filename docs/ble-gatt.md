# BLE GATT

グラスがペリフェラル、スマホがセントラル。アドバタイズ名は `ShodoGhost`（`GattProfile.LOCAL_NAME`）。

レガシー広告は 31byte なので、広告パケットは Flags と 128bit の Service UUID だけにする。名前はスキャンレスポンスに置く。

## UUID

ベース `7c3a9e20-1b4f-41a0-8d2e-6f0a1b2c3d1x`。バージョンニブルは 4、バリアントは 8。

| 名前 | UUID | プロパティ | 方向 | 中身 |
| --- | --- | --- | --- | --- |
| Service | `7c3a9e20-1b4f-41a0-8d2e-6f0a1b2c3d10` | — | — | Shodo Ghost Link |
| Command | `...3d11` | Write | スマホ → グラス | 制御メッセージ。Write With Response |
| Status | `...3d12` | Notify | グラス → スマホ | STATE、TOUCH、ACK、ERROR、HELLO_ACK、CAPTURE_READY、WIFI_*、PONG |
| Touch | `...3d13` | Write Without Response | スマホ → グラス | `TOUCH` だけ。リモコン |
| Payload | `...3d14` | Write, Notify | 双方向 | `STROKE_PACK` は Write、`CORRECTION_PACK` は Notify |
| SessionInfo | `...3d15` | Read | グラス → スマホ | `SESSION_INFO` フレーム |

Status と Payload に Client Characteristic Configuration Descriptor `00002902-0000-1000-8000-00805f9b34fb` を付ける。

標準の Battery Service `0x180F` は使わない。バッテリは `STATE` と `SESSION_INFO` に入っている。

## 接続手順

1. グラスは Activity が resumed のあいだ広告する。消灯でプロセスが止まれば広告も止まる。これは未接続として扱う
2. スマホは Service UUID でフィルタしてスキャンする
3. 接続後、ボンディングを要求する。完了前の Command はグラスが捨てる
4. MTU。グラス（Android ペリフェラル）は `requestMtu(247)` を出してよい。iPhone は `requestMtu` を持たず、接続時にシステムが交換する。セントラルは `CBPeripheral.maximumWriteValueLength(for: .withResponse)` を見る。値が 182 未満（ATT MTU 185 未満）なら HELLO を送らず `BACKOFF`（`ErrorCode.MTU_TOO_SMALL`）。iOS の既定は 185 前後で、この下限と揃っている
5. サービス探索、Status と Payload の Notify を有効化、SessionInfo を Read
6. Command に `HELLO`。Status の `HELLO_ACK` で `BLE_READY`
7. 接続優先度は BALANCED。バースト中も BLE を HIGH にしない。大量データは Wi-Fi 側

## ATT の余裕

| ATT MTU | 値の最大 | フレーム上限 | ペイロード上限 |
| --- | --- | --- | --- |
| 185（下限） | 182 | 182 | 172 |
| 247（希望） | 244 | 244 | 234 |

フレームのオーバーヘッドは 10byte（ヘッダ 8 + CRC 2）。制御メッセージは下限 MTU の1フレームに収まることをテストしている。分割してよいのは筆画パックだけ。

## どのメッセージをどの Characteristic に載せるか

Command（Write）:

`HELLO`、`SET_MODE`、`EXEMPLAR_META`、`CALIBRATE_START`、`CALIBRATE_COMMIT`、`CAPTURE_NOW`、`SHOW_ADVICE`、`CLEAR_OVERLAY`、`BEGIN_WIFI_SESSION`、`END_WIFI_SESSION`、`PING`、`SET_HUD`、`SLEEP_HINT`

Status（Notify）:

`HELLO_ACK`、`STATE`、`TOUCH`、`CAPTURE_READY`、`ERROR`、`PONG`、`ACK`、`WIFI_READY`、`WIFI_CLOSED`

Touch（Write Without Response）:

`TOUCH`。グラスは自分のテンプル入力と同じ `GuideCommand` に変換する

Payload:

`STROKE_PACK`、`CORRECTION_PACK`

SessionInfo（Read）:

`SESSION_INFO`。値も同じフレーム形式（SOF から CRC まで）

## ACK

次のメッセージは既定で `ACK_REQUEST` が付く。`Protocol.defaultFlags` が立てる。

- `SET_MODE`
- `CALIBRATE_COMMIT`
- `CAPTURE_NOW`
- `BEGIN_WIFI_SESSION`
- `END_WIFI_SESSION`

グラスは Status に `ACK` を返す。`ackSequence` は、分割されているときは先頭フラグメントのシーケンス。ACK は再構成が終わってから1回。

`SET_MODE` を拒否するときは `AckStatus.REJECTED`。カメラを開いているときは `BUSY`。

## 再接続

`RetrySchedule.delayMs` は試行 0 から 1000、2000、4000、8000、以降 15000 ミリ秒。

再接続後、スマホは `STATE` を待つ。`overlayBits` に手本ビットが無ければ `EXEMPLAR_META` と `STROKE_PACK` を送り直す。保留 JPEG があるグラスは `CAPTURE_READY` を再送する。JPEG の実体はまだグラス側にあり、Wi-Fi バーストで送る。
