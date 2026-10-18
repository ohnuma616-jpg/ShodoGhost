# Sideloadly で iPhone に入れる

Buildkite の Hosted agents は、この組織では制限されている。iPhone へのインストールは Windows の Sideloadly で行う。Sideloadly は IPA に無料の Apple ID で署名して USB 経由で入れる。コンパイルはしない。

IPA はまだ無い。WSL で xtool が `ios/Phone` を iOS アプリに固める。ボタンの中身はこれまでと同じ `CAPTURE_NOW`（理由は電話、品質 90）。Bundle ID は `dev.shodoghost.iphone`。

パスワードと Apple ID は `xtool setup` と Sideloadly の入力欄にだけ入れる。

## 1. WSL で未署名の IPA を作る

Ubuntu の WSL で、リポジトリを最新にする。

```bash
cd ~/ShodoGhost
git pull
```

Swift 6.4 を入れる。既に `swift --version` が 6.4 なら飛ばす。

```bash
sudo apt-get update
sudo apt-get install -y binutils git gnupg2 libc6-dev libcurl4-openssl-dev libedit2 libgcc-13-dev libncurses-dev libpython3-dev libsqlite3-0 libstdc++-13-dev libxml2-dev libz3-dev pkg-config tzdata zip unzip zlib1g-dev libfuse2
curl -fL "https://download.swift.org/swift-6.4.1-release/ubuntu2404/swift-6.4.1-RELEASE/swift-6.4.1-RELEASE-ubuntu24.04.tar.gz" -o /tmp/swift.tar.gz
sudo mkdir -p /opt/swift
sudo tar -xzf /tmp/swift.tar.gz -C /opt/swift --strip-components=1
echo 'export PATH=/opt/swift/usr/bin:$PATH' >> ~/.bashrc
export PATH=/opt/swift/usr/bin:$PATH
swift --version
```

xtool を入れる。

```bash
curl -fL "https://github.com/xtool-org/xtool/releases/latest/download/xtool-$(uname -m).AppImage" -o /tmp/xtool
chmod +x /tmp/xtool
sudo mv /tmp/xtool /usr/local/bin/xtool
xtool --help
```

Xcode 27 の `.xip` をブラウザで落とす。Apple ID でログインし、使用許諾に同意する。`curl` では落ちない。入手先は [xtool の Linux 手順](https://xtool.sh/documentation/xtooldocs/installation-linux) にある。落としたファイルを WSL から見える場所に置く。Windows のダウンロードなら `/mnt/c/Users/ohnum/Downloads/` にある。

```bash
xtool setup
```

ログインは `1`（Password）。無料の Apple ID でよい。続けて `.xip` のパスを渡す。終わったら次で `darwin` が出る。

```bash
swift sdk list
```

IPA を作る。

```bash
cd ~/ShodoGhost
bash scripts/sideload-ipa.sh
```

できたファイルは `build/ShodoGhost.ipa`。Windows からは `\\wsl.localhost\Ubuntu\home\ohnum\ShodoGhost\build\ShodoGhost.ipa`。

## 2. Sideloadly で iPhone に入れる

iPhone で、設定 → プライバシーとセキュリティ → デベロッパモード をオンにし、再起動して確認する。

Windows では次の順にする。

1. Microsoft Store 版の iTunes と iCloud が入っていたら削除する。
2. [Sideloadly](https://sideloadly.io/) のページにある、Web 版の iTunes と iCloud を入れる。入れたあと Windows を再起動する。
3. 同じページから Sideloadly の Windows 版を入れる。
4. iPhone を USB でつなぎ、「このコンピュータを信頼」を選ぶ。
5. Sideloadly を開き、`ShodoGhost.ipa` をウィンドウへ落とす。
6. Apple ID を入れる。2ファクタなら [appleid.apple.com](https://appleid.apple.com) の「サインインとセキュリティ」で App 用パスワードを作り、パスワード欄にはそれを入れる。
7. バンドル ID は `dev.shodoghost.iphone` のままにする。Start を押す。
8. iPhone で、設定 → 一般 → VPN とデバイス管理 → デベロッパ App の自分の Apple ID → 信頼。

画面が「準備完了」になったら「撮影」。グラスは今のアプリのままで、同じ `CAPTURE_NOW` を受け取る。

無料の Apple ID では、署名は 7 日で切れる。切れたら同じ IPA を Sideloadly でもう一度入れる。同時に入る本数は 3 つまで。

## Buildkite

`.buildkite/pipeline.yml` は Mac の Hosted キューを使う。この組織では Hosted agents が制限されているので、そのビルドは始まらない。Platform Pro にすると、同じファイルでシミュレータ用の app が作れる。

Depot を Apps から入れると、Ubuntu で `:protocol:test` と `swift test` が走る。接続したあと main へ push すると始まる。グラスの APK は Windows の JDK 21 で作る。
