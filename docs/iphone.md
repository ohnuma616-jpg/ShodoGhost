# iPhone

コンパニオンは iPhone です。最小バージョンは iOS 17。UI は SwiftUI。通信の実装は Swift Package `ios/Protocol`（モジュール名 `ShodoGhostProtocol`）で、Phase 4 のアプリがこれをリンクします。

符号器はグラス側の Kotlin と別物です。`fixtures/canonical.txt` を Swift が復号して再符号化し、バイト列が一致することを `swift test` が確認します。

## BLE

iPhone はセントラルです。API は CoreBluetooth。

- スキャンのサービス UUID は `GattProfile.service`
- バックグラウンドスキャンで見つかるには、広告パケット自体にこの 128bit UUID が入っている必要がある。Phase 1 の広告レイアウトはそれを満たしている
- `Info.plist` の `UIBackgroundModes` に `bluetooth-central`
- `CBCentralManager` は状態の復元識別子 `dev.shodoghost.central` で作る。アプリが停止していても Notify で短時間起こせる
- 復元で起きた処理は短い。Wi-Fi の待受はここでは開かない
- 使用宣言は `NSBluetoothAlwaysUsageDescription`

ペアリングは iOS のシステム UI に出す。Phase 2 のグラス GATT は Command / Payload / SessionInfo を暗号化必須にする。iPhone がそこに触れた時点でペアリングが始まる。HELLO より前の書き込みはグラスが捨てる。

`requestMtu` は iOS に無い。書き込み長は `maximumWriteValueLength(for: .withResponse)`。182 バイト未満ならこの契約の MTU 下限を満たさない。`PhoneSession` はそのとき HELLO を送らず `BACKOFF` へ進む。

セッション本体は `PhoneSession`。電波は `CoreBluetoothPhoneRadio`。`prepare` がマネージャを作り、Service UUID でスキャンし、Status と Payload の Notify を有効にしてから MTU（書き込み長 + 3）をセッションへ渡す。切断ではペリフェラルを捨て、次の `prepare` がスキャンし直す。

再接続間隔は `RetrySchedule` のまま（1 秒から 15 秒）。`PhoneSession.scheduleRetry` の既定がこの待ちを `DispatchQueue.global` に載せる。アプリは差し替えてよい。

実装の対応表とモックパイプは [phase-2.md](phase-2.md)。

## Wi-Fi バースト

Android の Foreground Service に相当する常駐は iOS に無い。JPEG の待受は、練習画面が前面にあるあいだだけ開く。実機の API は `NWListener`。Linux で同じ契約を検証する実装は `TcpWifiListener` で、`PracticeCoordinator` が前面ゲートと、Wi-Fi を閉じてからの添削を持つ。手順は [phase-4.md](phase-4.md)。

- 権限文は `NSLocalNetworkUsageDescription`
- アドレスは BLE の `BEGIN_WIFI_SESSION` でグラスへ渡す。Bonjour には依存しない
- グラスから iPhone へ TCP で繋ぐ。iPhone からグラスへ繋ぎにいかない
- ハードタイムアウト 20 秒は契約どおり。失敗したら BLE のまま添削待ちにしないで、前面に戻ってからやり直す
- 背面で `CAPTURE_READY` を受けたら保持し、前面に戻ったときにバーストを始める。未送信 JPEG はグラス側の 1 枚と、iPhone 側の保留通知が対になる

推奨するネットワークは iPhone のインターネット共有（パーソナルホットスポット）です。グラスをその SSID に一度参加させる。参加設定は RayNeo のシステム Wi-Fi で行い、このアプリはパスワードをグラスへ打ち込まない。待受アドレスはブリッジ側（多くは `172.20.10.1`）を優先し、無ければ Wi-Fi インターフェイスの IPv4 を使う。

同じ LAN に両方を置く構成も動く。クライアント同士を隔離するアクセスポイントではグラスから iPhone へ届かない。

## 添削と入力

四隅検出と筆画の幾何は、LAN 上の Python を本線にする。OpenCV を iOS アプリへ同梱しない。Python が無いときは、iPhone の画面で四隅を手で合わせて `CALIBRATE_COMMIT` を送る。

API キーとアカウントは Keychain に置く。CloudKit や Firebase はこの契約に入れない。

文字入力、用紙の選択、手本一覧、撮影ボタンは SwiftUI 側だけに出す。グラスにキーボードは出さない。

撮影ボタンは `TouchAction.longPress` と同じ `TOUCH` を Touch characteristic へ書く。テンプルの長押しを OS が取っても、iPhone のボタンが撮影の正になる。
