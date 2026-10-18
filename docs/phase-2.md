# Phase 2 — セッション

オンエアのパケットは Phase 1 のまま。このフェーズで足したのは、そのフレームを GATT の読み書きに載せるセッションと、Linux で往復できるモックパイプ。

新しいメッセージ型は無い。Android の `BluetoothGattServer` モジュールは置いていない。この環境に Android SDK が無く、コンパイルできないため。グラス側の本体は JVM の `GlassSession` で、将来の GATT サーバは UUID でそこへ委譲する。

## 役割

| クラス | 側 | すること |
| --- | --- | --- |
| `GlassSession` | グラス | 広告開始、ボンド、MTU、Command / Touch / Payload、Status の Notify |
| `PhoneSession` | iPhone | 探索から HELLO、ACK 待ち、Wi-Fi の出入り、切断後の再試行 |
| `CoreBluetoothPhoneRadio` | iPhone | スキャン、Notify、Write。`canImport(CoreBluetooth)` のときだけコンパイルされる |
| `TcpPhoneRadio` | テスト | モックパイプのセントラル |
| `LoopbackGlassHost` | テスト | モックパイプのペリフェラル。127.0.0.1 で 1 接続を受けて終わる |

`PhoneSession` は電波を知らない。`PhoneRadio` を差し替える。本番は `CoreBluetoothPhoneRadio`、試験は `TcpPhoneRadio`。

## グラスが受け付ける条件

Command / Touch / Payload は、ボンド済み、ATT MTU が 185 以上、相が `NEGOTIATING` から `WIFI_TEARDOWN` までのときだけ通す。ボンド前の書き込みは捨てる。

`NEGOTIATING` で受ける制御は `HELLO` だけ。Touch と Payload はこの相では捨てる。

MTU が 185 未満のときは `DISCOVERING` → `CONNECTING` → `NEGOTIATING` まで進めてから `HELLO_REJECTED` で `BACKOFF` する。HELLO は送らない。バージョン不一致の `HELLO` も `HELLO_ACK.accept = false` で同じ `BACKOFF`。

`CAPTURE_NOW` は熱が `HOT`、またはカメラが `CLOSED` でないとき `ACK BUSY` と `ERROR` を返し、リスナは呼ばない。

## Wi-Fi の戻り

`END_WIFI_SESSION` の ACK が OK のとき、両端が同じ信号を使う。

- `WIFI_WAKE` にいるなら `STOP`（`TRANSFER_FINISHED` はここでは無視される）
- `TRANSFER` にいるなら `TRANSFER_FINISHED`
- 続けて `WIFI_TEARDOWN` なら `WIFI_TORN_DOWN` で `BLE_READY`

グラスは ACK のあとに `STATE` を Notify する。ソケットが実際に閉じたときは別に `WIFI_CLOSED` を出せる。iPhone は `WIFI_CLOSED` でも同じ戻りを行う。すでに `BLE_READY` なら状態は動かない。

`BEGIN_WIFI_SESSION` は `BLE_READY` のときだけ受ける。それ以外は `ACK BUSY` で相を変えない。iPhone は ACK が OK のときだけ `BEGIN_WIFI` を進める。

## 再接続

`PhoneSession.scheduleRetry` の既定は `DispatchQueue.global` で、`RetrySchedule` の 1 秒、2 秒、4 秒、8 秒、以降 15 秒を待つ。テストはこの関数を空に差し替える。

`CoreBluetoothPhoneRadio` は切断でペリフェラルと Characteristic を捨てる。次の `prepare` がスキャンし直す。`prepare` の時点でマネージャが無ければ、復元識別子 `dev.shodoghost.central` で作る。

TCP ラジオは `close` のあと同じソケットへは書けない。ループバック試験は再接続を見ない。

## モックパイプ

オンエアのフレームではない。テストと `LoopbackGlassHost` だけが使う。

1 レコードは op 1 バイト、長さ 4 バイト（リトルエンディアン）、本体。本体の上限は 1MiB。TCP は `127.0.0.1`、`TCP_NODELAY`、待ち受けは 1 クライアント。

| op | 値 | 向き |
| --- | --- | --- |
| COMMAND | 1 | スマホ → グラス |
| STATUS | 2 | グラス → スマホ |
| TOUCH | 3 | スマホ → グラス |
| PAYLOAD_WRITE | 4 | スマホ → グラス |
| PAYLOAD_NOTIFY | 5 | グラス → スマホ |
| READ_REQ | 6 | スマホ → グラス |
| READ_RSP | 7 | グラス → スマホ |
| LINK_UP | 8 | スマホ → グラス。本体は ATT MTU の u16 LE |
| LINK_DOWN | 9 | スマホ → グラス |
| BOND | 10 | スマホ → グラス |

ホストは `accept` の前に ready ファイルへ `listening <port>` を書き、標準出力に `LISTENING` を出す。

## GATT へ接続するとき

Android のコールバックは次の対応にする。暗号化必須の Characteristic に触れた結果ボンドが完了してから `onBonded`。MTU 交換のあと `onLinkUp`。切断で `onLinkDown`。

| UUID | 呼び出し |
| --- | --- |
| Command `...3d11` | `onCommand` |
| Touch `...3d13` | `onTouch` |
| Payload `...3d14` Write | `onPayload` |
| Status `...3d12` Notify | `GlassRadio.notifyStatus` |
| Payload Notify | `GlassRadio.notifyPayload` |
| SessionInfo `...3d15` Read | `sessionInfoFrame()` |

## 動かし方

```bash
./gradlew :protocol:test
swift test --package-path ios/Protocol
./scripts/loopback-check.sh
```

ループバックは既定ポート 18771。`scripts/loopback-check.sh` が Gradle のホストを上げ、`SHODO_LOOPBACK_PORT` を付けて `kotlinGlassLoopback` だけを走らせる。Swift が PATH に無ければ `$HOME/swift/usr/bin` を足す。

この試験が見る経路は、HELLO のノンス一致、`PING`、`SET_MODE`、20 画 × 30 点の `STROKE_PACK`（MTU 247 で分割される）、手本ビット、`BEGIN_WIFI` の `172.20.10.1:18765`、助言 2 行、`END_WIFI` のあと `BLE_READY`。
