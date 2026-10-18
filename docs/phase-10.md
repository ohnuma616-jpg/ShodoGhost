# Phase 10 — 写真を iPhone の画面へ

撮影ボタンのあと、グラスが保持している JPEG をホットスポット越しに送り、iPhone の画面に出す。添削はまだ始めない。

iPhone は `CAPTURE_READY` を受けると `NWListener` で全インターフェイスを待受し、`BEGIN_WIFI_SESSION` に IPv4 とポートと 16 バイトのトークンを載せる。アドレスは `172.20.10.1` を優先する。グラスは BLE の ACK のあと、別スレッドで TCP を開き、WebSocket（`/v1/session`、`shodo-ghost.v1`）で hello、jpeg_begin、16KiB 以下のバイナリ、jpeg_end を送る。iPhone はバイト数と CRC32 が `CAPTURE_READY` と一致したときだけ画像にする。そのあと `END_WIFI_SESSION` を送る。

待受は `127.0.0.1` に縛らない。Linux の契約テストが使う `TcpWifiListener` はループバックのまま。グラス側の送信は `WifiJpegClient`。

切断するとグラスは広告を止めて、すぐ広告し直す。HUD が「準備完了」のまま iPhone が探し続ける状態を、アプリの再起動なしで戻す。

確認は iPhone の画面に写真が出ること。`files/still.jpg` の取り出しは、画面に出なかったときの控え。

入れるものは二つ。グラスは今までと同じ debug APK。iPhone は GitHub Actions の新しい `ShodoGhost-ipa` を Sideloadly で入れ直す。手順は [cloud-ci.md](cloud-ci.md)。

撮影の前に、iPhone のインターネット共有をオンにし、グラスをその SSID へ RayNeo のシステム Wi-Fi から一度参加させる。アプリはパスワードを打たない。初めての受信で「ローカルネットワーク」の許可が出る。許可のあいだに 20 秒を超えたら、許可したあとにもう一度撮影する。
