# Phase 5 — スリープ、再接続、ラジオの保持

画面が消えると Wi-Fi だけを放す。BLE はプロセスが生きているあいだ維持する。未送信の JPEG はメモリに 1 枚だけ残し、再接続の `HELLO` のあとで `CAPTURE_READY` を送り直す。ディスクには書かない。実機の mAh は測っていない。新しいオンエアのパケットは無い。

## ラジオ

`PowerPolicy.allows` が、相ごとに持ってよいものを決める。

| 相 | 持ってよいもの |
| --- | --- |
| `IDLE` | なし |
| `BLE_READY`、探索、折衝、`BACKOFF`、`WIFI_TEARDOWN` | BLE。カメラは `BLE_READY` でパネルが点いているときだけ |
| `WIFI_WAKE`、`TRANSFER` | BLE、Wi-Fi、WakeLock。カメラは開かない |
| パネル消灯 | Wi-Fi と WakeLock とカメラを外す。相が転送中なら `TIMEOUT` で `BLE_READY` に戻す |

`SLEEP_HINT` の `displayOffDelayMs` が 0 ならその場で消灯する。0 より大きければ、そのミリ秒が過ぎてから `onClock` が消灯する。消灯中の `BEGIN_WIFI` は `BUSY` で、ラジオを取らない。パネルの点灯はグラス側の `noteDisplayOn` で、パケットは無い。点灯しても Wi-Fi は開き直さない。

転送を始めてから 20 秒を超えると `ERROR WIFI_TIMEOUT` と `WIFI_CLOSED` を出し、`BLE_READY` に戻る。iPhone は `WIFI_TIMEOUT` で自分の相も `BLE_READY` に戻す。

## メモリ 1 枚

`publishCaptureReady` は直前の 1 枚を置き換える。転送が `TRANSFER` まで進んで `END_WIFI` で閉じたときだけ捨てる。ソケットが開く前の中止、20 秒切れ、消灯、BLE 切断では残す。

切断中に撮った 1 枚は通知しない。次の `HELLO` が成功したあと、`STATE` に続けて `CAPTURE_READY` を再送する。JPEG のバイト列はグラスのメモリにあり、BLE には載らない。

手本のオーバーレイビットも、プロセスが生きていれば切断をまたぐ。ビットが無い `STATE` を再接続後に見たら、iPhone は覚えていた `EXEMPLAR_META` と `STROKE_PACK` を 1 回送り直す。ビットが残っていれば送り直さない。

プロセスが死ぬと `GlassSession` ごと消える。JPEG も手本ビットも戻らない。`PowerPolicy.persistsAcrossProcessDeath` は false。

## 再接続

グラスが `BACKOFF` のときリンクが戻ったら、`RETRY` から `NEGOTIATING` まで進めて次の `HELLO` を受ける。iPhone の再試行間隔はこれまでどおり 1 秒、2 秒、4 秒、8 秒、以降 15 秒。

## 動かし方

```bash
./gradlew :protocol:test
swift test --package-path ios/Protocol
```

見るのは、消灯で Wi-Fi が落ちて JPEG が残ること、20 秒で `WIFI_TIMEOUT` になること、転送完了では JPEG を忘れること、再接続で `CAPTURE_READY` と手本ビットが戻ること、ビットが無いときだけ iPhone が手本を送り直すこと。

## 測っていないこと

245mAh に対する実消費、スリープからの広告復帰時間、RayNeo OS が Wi-Fi を切るタイミングは、この環境に実機が無いので未計測。ここにあるのは、その測定の前に固定する保持規則。
