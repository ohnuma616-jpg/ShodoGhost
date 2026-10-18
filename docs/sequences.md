# 通信シーケンス

状態名は `LinkPhase`。副作用の順は `LinkStateMachine.step` の `effects`。

## リンク

```mermaid
sequenceDiagram
    participant Glass as Glass GATT server
    participant Phone as Phone central
    Glass->>Glass: resumed で広告 ShodoGhost
    Phone->>Glass: Service UUID でスキャン
    Phone->>Glass: Connect, bond
    Phone->>Glass: MTU 交換（iOS はシステム。下限 185、希望 247）
    alt MTU が 185 未満
        Phone->>Phone: NEGOTIATING まで進めて HELLO せず BACKOFF
    else MTU 成立
        Phone->>Glass: Notify 有効化, Read SESSION_INFO
        Phone->>Glass: Command HELLO
        Glass-->>Phone: Status HELLO_ACK
        Note over Phone,Glass: BLE_READY
    end
```

`HELLO_ACK.accept` が 0 のときは `HELLO_REJECTED` で `BACKOFF`。

`END_WIFI_SESSION` の ACK が OK のとき、`WIFI_WAKE` からは `STOP`、`TRANSFER` からは `TRANSFER_FINISHED` で `WIFI_TEARDOWN` に入り、続けて `WIFI_TORN_DOWN` で `BLE_READY` に戻る。`WIFI_CLOSED` が後から来ても、すでに `BLE_READY` なら相は動かない。セッション実装は [phase-2.md](phase-2.md)。

## 手本を載せる

```mermaid
sequenceDiagram
    participant Phone
    participant Glass
    Phone->>Glass: SET_MODE TRACE, HANSHI
    Glass-->>Phone: ACK
    Phone->>Glass: EXEMPLAR_META
    Phone->>Glass: Payload STROKE_PACK（必要なら分割）
    Glass->>Glass: 骨格をシアン、外形を緑、2px
```

## 四隅キャリブレーション

```mermaid
sequenceDiagram
    participant Glass
    participant Phone
    participant Analyzer as Phone or Python
    Phone->>Glass: CALIBRATE_START ONE_SHOT
    Glass->>Glass: IMX681 を開き JPEG 1枚、閉じる
    Glass-->>Phone: CAPTURE_READY
    Phone->>Glass: BEGIN_WIFI_SESSION
    Note over Glass: WakeLock と Wi-Fi Lock
    Glass->>Phone: WebSocket hello
    Glass->>Phone: JPEG
    Phone->>Analyzer: 四隅検出
    Analyzer-->>Phone: PaperQuad と mm
    Phone->>Glass: END_WIFI_SESSION
    Glass->>Glass: ソケットとロックを解放し BLE_READY
    Phone->>Glass: CALIBRATE_COMMIT
    Glass->>Glass: Homography と中央十字
```

SLAM 方式（`CalibMethod.SLAM`）はこの図に出さない。アダプタが無いビルドは `UNSUPPORTED_MODE` を返す。

## 書き終わりと添削

Wi-Fi はアップロードだけに使う。VLM の待ち時間は BLE に戻してから過ごす。

```mermaid
sequenceDiagram
    participant Glass
    participant Phone
    participant Analyzer as AnalysisBackend
    Glass->>Glass: 長押し、またはスマホの TOUCH
    Glass->>Glass: 静止画1枚、カメラ解放
    Glass-->>Phone: CAPTURE_READY
    Phone->>Glass: SHOW_ADVICE「添削中」
    Phone->>Glass: BEGIN_WIFI_SESSION purpose=UPLOAD_JPEG
    Glass->>Phone: JPEG
    Phone->>Glass: 転送完了で Wi-Fi を閉じる
    Note over Glass: BLE_READY、カメラは閉じたまま
    Phone->>Analyzer: critique
    Analyzer-->>Phone: 赤線ポリラインと2行
    Phone->>Glass: SHOW_ADVICE
    Phone->>Glass: CORRECTION_PACK
    Glass->>Glass: REVIEW。線は用紙座標のまま射影
```

`AnalysisBackend` が LAN の Python を呼ぶ場合、その WebSocket はスマホと PC のあいだで、グラスは関与しない。

## スリープと Wi-Fi の失敗

```mermaid
sequenceDiagram
    participant Glass
    participant Phone
    Note over Glass,Phone: BLE_READY
    Phone->>Glass: BEGIN_WIFI
    alt ソケットが 20秒以内に開かない
        Glass->>Glass: CLOSE_WIFI_SOCKET, RELEASE_RADIOS
        Note over Glass,Phone: BLE は BLE_READY のまま
        Glass-->>Phone: ERROR WIFI_TIMEOUT
    else 画面消灯で Wi-Fi だけ死ぬ
        Glass->>Glass: TIMEOUT として BLE_READY へ戻る
    else BLE も死ぬ
        Phone->>Phone: LINK_LOST, BACKOFF
        Phone->>Phone: 1s, 2s, 4s, 8s, 以降 15s で再スキャン
    end
```

画面消灯でアプリプロセスが生きていれば BLE は維持してよい。広告が止まれば iPhone からは `LINK_LOST` に見える。

iPhone の待受は練習画面が前面のときだけ開く。背面やロック中に `BEGIN_WIFI` は送らない。`CAPTURE_READY` が背面で届いたらアプリはそれを覚え、前面に戻ってからバーストを始める。詳細は [iphone.md](iphone.md)。

## 未接続で書いたとき

```mermaid
sequenceDiagram
    participant Glass
    participant Phone
    Note over Glass: 警告線を出す。手本はメモリにあれば残す
    Glass->>Glass: 長押しで JPEG を1枚だけ保持
    Phone->>Glass: 再接続、HELLO
    Glass-->>Phone: STATE（手本ビットはプロセスが生きていれば立つ）
    Glass-->>Phone: CAPTURE_READY を再送
    Phone->>Glass: BEGIN_WIFI_SESSION
    Glass->>Phone: 保留 JPEG
```

プロセスが死んでいれば手本ビットは落ちる。スマホがパックを送り直す。保留 JPEG も消える。Phase 5 で、ディスクへの保存はしないと決めた。生きているプロセスのメモリ 1 枚まで。手順は [phase-5.md](phase-5.md)。
