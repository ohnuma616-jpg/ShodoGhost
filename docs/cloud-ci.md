# Windows から iPhone アプリをコンパイルする

推奨は GitHub Actions の `macos-latest` です。公開リポジトリなら Mac のビルドは無料で、本物の `xcodebuild` が実機用の IPA を出します。iPhone へ入れるのは、これまで通り Windows の Sideloadly です。Sideloadly が無料の Apple ID で署名します。

このリポジトリの正本は Origin です。`.github/workflows/ios-ipa.yml` を置いただけでは Mac は動きません。GitHub へ push したときだけ `macos-latest` が走ります。Depot を Origin につなぐとランナーは Ubuntu になり、`xcodebuild` はそこでは動きません。だからこの IPA 用ワークフローは `.depot/workflows` には置いていません。

IPA はまだありません。下の GitHub のビルドが最初のコンパイルです。

## 選ばないもの

Expo Application Services は React Native / Expo 用です。このアプリは SwiftUI なので、EAS ではコンパイルできません。

Codemagic と Bitrise もクラウドの Mac で `xcodebuild` します。接続先は GitHub などのリポジトリで、無料で回せる時間は GitHub Actions の公開リポジトリより短いです。先に GitHub Actions で足ります。

Buildkite の Hosted agents は、この組織では制限されています。Platform Pro にすると `.buildkite/pipeline.yml` の Mac が使えます。

コードを GitHub に出したくないときは、下の「WSL の xtool」です。Xcode 27 の `.xip` を自分の PC に落とします。

## GitHub Actions

1. GitHub で公開リポジトリを作る。非公開だと macOS の消費が 10 倍になり、無料枠ではすぐ終わります。
2. Origin の `main` を、その GitHub リポジトリの `main` へ push する。
3. GitHub の Actions タブで `ios-ipa` が終わるのを待つ。`ios/**` を変えた push と、手動の workflow_dispatch で始まります。
4. その実行の Artifacts から `ShodoGhost-ipa` を落とす。中身は `ShodoGhost.ipa`。
5. 下の Sideloadly にその IPA を落とす。

ワークフローは `xcodebuild -sdk iphoneos` で署名なしのアプリを作り、仮の署名を付けてから IPA に包みます。Apple の Team も fastlane も要りません。Bundle ID は `dev.shodoghost.iphone`。ボタンは `CAPTURE_NOW`（理由は電話、品質 90）です。

## Sideloadly で iPhone に入れる

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

画面が「準備完了」になったら「撮影」。グラスは今のアプリのままで、同じ `CAPTURE_NOW` を受け取ります。

無料の Apple ID では、署名は 7 日で切れる。切れたら同じ IPA を Sideloadly でもう一度入れる。同時に入る本数は 3 つまで。パスワードは Sideloadly の入力欄にだけ入れる。

## WSL の xtool

GitHub に出さないときに使います。Ubuntu の WSL でリポジトリを最新にします。

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

Xcode 27 の `.xip` をブラウザで落とす。Apple ID でログインし、使用許諾に同意する。`curl` では落ちない。入手先は [xtool の Linux 手順](https://xtool.sh/documentation/xtooldocs/installation-linux) にある。Windows のダウンロードなら `/mnt/c/Users/ohnum/Downloads/` から WSL が見えます。

```bash
xtool setup
```

ログインは `1`（Password）。無料の Apple ID でよい。続けて `.xip` のパスを渡す。終わったら次で `darwin` が出る。

```bash
swift sdk list
cd ~/ShodoGhost
bash scripts/sideload-ipa.sh
```

できたファイルは `build/ShodoGhost.ipa`。Windows からは `\\wsl.localhost\Ubuntu\home\ohnum\ShodoGhost\build\ShodoGhost.ipa`。入れ方は上の Sideloadly と同じです。

## Depot

Depot を Origin の Apps から入れると、Ubuntu で `:protocol:test` と `swift test` が走ります。接続したあと main へ push すると始まります。グラスの APK は Windows の JDK 21 で作ります。
