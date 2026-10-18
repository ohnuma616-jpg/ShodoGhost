# Phase 8 — BLE で撮影

グラスは Activity が生きているあいだ `ShodoGhost` を広告する。緑の行に「広告中」と出る。一時的に画面が止まっても広告は続ける。パケットは Service UUID だけ、名前はスキャンレスポンス。Command、Touch、Payload、SessionInfo は暗号化が必要で、ボンドが済むまで `GlassSession` は書き込みを捨てる。MTU が 185 未満の通知は無視する。iPhone は接続時にその交換を行う。

`CAPTURE_NOW` のリスナは GATT のロックを持ったままカメラを開かない。別スレッドの `GlassStillShot` がカメラ 0 を 1 枚開いて閉じ、`CAPTURE_READY` を Status へ載せる。引き出すための写しは `files/still.jpg`。起動しただけでは撮らない。

Xcode のアプリはまだ無い。同じバイト列は iPhone の nRF Connect から書ける。Command は `7c3a9e20-1b4f-41a0-8d2e-6f0a1b2c3d11`、Status は `...3d12`。

HELLO（シーケンス 1、MTU 185、ノンス `01` から `08`）:

```text
a5 01 01 02 01 00 0c 00 01 b9 00 01 01 02 03 04 05 06 07 08 4a 8a
```

CAPTURE_NOW（電話、品質 90、ACK 要求）:

```text
a5 01 06 03 02 00 02 00 02 5a ef b6
```

Status の Notify を有効にしてから HELLO を書く。ペアの確認が出たら許可し、HELLO が拒否されたら同じバイト列をもう一度書く。そのあと CAPTURE_NOW を書く。
