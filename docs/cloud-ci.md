# クラウドで iPhone をコンパイルする

手元の Windows には Xcode が無い。iPhone アプリのコンパイルは Buildkite の Mac が行う。Origin 自体はジョブを実行しない。

Depot を Origin の Apps から入れると、Ubuntu で `:protocol:test` と `swift test` が走る。ランナーは Ubuntu なので、`xcodebuild` はそこでは走らない。

この環境では `xcodebuild` を実行していない。アプリターゲットの最初のコンパイルは、下の Buildkite のビルドになる。

## Buildkite でシミュレータ用の app を作る

1. [cursor.com/codebase](https://cursor.com/codebase) を開き、Settings → Apps を開く。リポジトリの Settings → Apps → Manage Apps からでも同じ画面に着く。
2. Buildkite を選び、このリポジトリへアクセスを許可してインストールする。Origin が Buildkite へ移る。
3. Buildkite で組織を作るか、既存の組織を選ぶ。Origin 経由の申し込みには評価期間がある。
4. Clusters → Queues を開く。macOS の Hosted キューが無いときは、Hosted、Machine type は macOS、Capacity は Medium で作る。キーを控える。新しい組織には `macos-15-medium` が用意されている。
5. キーが `macos-15-medium` 以外なら、`.buildkite/pipeline.yml` の `queue` をそのキーに変えて main へ push する。
6. Pipelines → New Pipeline。Git scope は Origin（手動で URL を貼らない）。Repository はこのリポジトリ。YAML Steps は Pipeline upload。`.buildkite/pipeline.yml` があるので、作成画面はそれを選ぶ。
7. Create Pipeline のあと New Build。ブランチは `main`。

成功すると成果物 `build/ShodoGhost-simulator.zip` がある。中身は iOS Simulator 向けの `ShodoGhost.app` で、グラス側がすでに受け取っている `CAPTURE_NOW`（理由は電話、品質 90）を送るアプリのコンパイル結果。この zip は iPhone には入らない。

ジョブがキューで止まったままなら、キューのキーが pipeline.yml と違う。Agents が 0 の macOS キューは、Hosted の Mac をそのキューに作る。

## 実機に入れる

署名の Secrets が無いビルドは、シミュレータ用の zip で終わる。実機用は次の 4 つを、パイプラインの Settings → Secrets に入れる。

| 名前 | 中身 |
| --- | --- |
| `APPLE_TEAM_ID` | developer.apple.com の Membership にある Team ID |
| `APP_STORE_CONNECT_KEY_ID` | API キーの ID |
| `APP_STORE_CONNECT_ISSUER_ID` | API キーの Issuer ID |
| `APP_STORE_CONNECT_KEY` | `AuthKey_....p8` の中身。`BEGIN PRIVATE KEY` を含むテキストか、その Base64 |

キーは App Store Connect の「ユーザとアクセス」→「統合」→ App Store Connect API で作る。役割は Admin。`.p8` は作成時の一度しかダウンロードできない。

Secrets を入れて同じパイプラインをもう一度ビルドすると、development の `build/ShodoGhost.ipa` ができる。入るのは、その Team の Devices に UDID を登録した iPhone だけ。Bundle ID は `dev.shodoghost.iphone`。

Mac を使わず iPhone へ入れるときは、パイプラインの環境変数 `IOS_DISTRIBUTE` を `testflight` にする。ビルドは App Store Connect へアップロードする。先に App Store Connect で App を作り、Bundle ID を `dev.shodoghost.iphone` に合わせる。iPhone では TestFlight から入れる。内部テストに自分を加える。これには Apple Developer Program の加入が要る。

## Depot で契約テストを走らせる

1. 同じ Apps 画面で Depot を入れ、Depot 側で組織を作る。
2. main に `.depot/workflows/checks.yml` がある。接続より前の push は走らないので、接続したあと main へ何か push する。
3. Depot CI の画面に checks が出る。中身は JDK 21 で `:protocol:test`、Swift 6.2 で `swift test --package-path ios/Protocol`。

グラスの APK はこのジョブでは作らない。これまで通り Windows の JDK 21 で `:glass:assembleDebug` する。
