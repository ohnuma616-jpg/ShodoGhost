# Phase 9 — iPhone の撮影ボタン

`ios/App/ShodoGhost.xcodeproj` を Mac の Xcode で開く。署名チームを選んで、iPhone に入れる。最小は iOS 17。

アプリは起動すると `ShodoGhost` を探し、HELLO を送る。画面が「準備完了」になったら「撮影」が押せる。中身は Command への `CAPTURE_NOW`（理由は電話、品質 90）で、nRF Connect の2通目と同じです。撮れたバイト数を画面に出します。JPEG の受信はまだ始めません。

この Linux には Xcode が無いので、アプリターゲットはここではコンパイルしない。
