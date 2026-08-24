# ReproDroid Runner

ReproDroid AndroidアプリからJobを受け、模擬ビルドまたはallowlist登録済みAndroid OSSの実ビルドを実行するPC側常駐プロセスです。

## 現在の状態

Phase 2Bの固定release build profileとAndroid比較E2Eに対応しています。Phase 2CはAndroid側のtrust／update／install／settings統合であり、Runner API v1とSQLite schema v4は変更しません。

- `127.0.0.1:8080`へbindするKtor HTTP API v1
- SQLiteへ永続化する単一workerの非同期Jobキュー
- 外部プロセスを起動しない模擬成功・模擬失敗
- 差分取得できるログと模擬APKメタデータ
- 起動時に実行途中のJobを`INTERRUPTED`へ移す復旧処理
- GitHub ref解決、commit/RCE確認ゲート、detached checkout
- Gradle公式checksumによるdistribution/Wrapper JAR検査
- 固定task実行、timeout/cancel、APK検出、Build Environment Manifest、監査ログ
- API、成功・失敗、cancel、再起動永続化、安全ゲートの自動テスト
- build workspaceとは別の専用artifact領域へのAPK保存
- `SUCCEEDED` Jobの登録済みAPKだけを返すcontent endpoint
- 配信前のpath confinement、symbolic link、size、SHA-256再検査
- APK MIME type、Content-Length、SHA-256由来ETag
- repositoryとrevisionを組にしたexact recipe allowlist
- API／SQLiteへ永続化するrecipe ID、variant、Java major
- recipe別build JDKのpath／major検査と固定Java executableによるWrapper起動

Phase 1EではMicroG-REの固定taskをRunner APIとAndroid UIから実行し、同一commitから同一size・SHA-256のAPKを生成した。content endpointからの取得、同じstate directoryでのRunner再起動後のJob／artifact／log cursor復元、Windows Android Emulatorからのdownloadと標準PackageInstaller E2Eまで確認済みである。詳細は[Phase 1E検証レポート](../reprodroid-project/reports/2026/08/2026-08-21-phase-1e.md)、履歴と最終状態は[Phase 1E再開・完了記録](../reprodroid-project/docs/handoffs/phase-1e-resume.md)を参照してください。

## Phase 2のRunner境界

Phase 2では、公式APKまたは開発者公開APKと更新情報をAndroidアプリ側で取得し、Android側で参照APK比較と更新候補判定を行います。Runnerは引き続きソース取得、allowlist済み実ビルド、Build Environment Manifest、ビルドartifact配信を担当し、公式APKを取得するAPIは追加しません。

Phase 2BはMicroG-RE `TAG 6.1.4`だけを許可する`defaultRelease` profileを追加しました。Runner本体はJDK 21で動かし、外部buildは検査済み`REPRODROID_JDK_18_HOME`のTemurin 18で`clean :play-services-core:assembleDefaultRelease`を実行します。`effectiveBuild`にはrecipe ID、variant、Java majorを追加し、Androidが対象同一性をfail closedで検査します。Runnerの`SUCCEEDED`はbuild成功だけを表し、配布元APKとの`MATCH`／`DIFFERENT`／`INCOMPARABLE`はAndroid側へ保持します。詳細は[ADR-0009](../reprodroid-project/docs/adr/0009-phase-2-reference-apk-and-update-boundary.md)と[ADR-0010](../reprodroid-project/docs/adr/0010-phase-2b-executable-apk-content-comparison.md)を参照してください。

この固定taskが生成するAPKは上流workflowの後段sign action前なのでunsignedです。Runnerはartifactのsize／SHA-256／配信完全性を保証しますが、比較用artifactへ署名を追加しません。Android側はcomparison専用経路でだけ扱い、通常のinstaller導線から分離します。

将来ReproDroid鍵でlocal comparison artifactを署名する案は候補として残しますが、Phase 2への採用は確定していません。Runnerは信頼済みrepositoryであってもGradle build scriptによる任意コード実行を許す境界にあるため、private keyを現行Runner process／build workspaceへ置きません。採用する場合は鍵の配置、分離、backup、rotation、signer continuityを別ADRで確定してから実装します。

## リポジトリ構成

- `reprodroid-runner`: 本リポジトリ。Runner実装
- `reprodroid`: Androidクライアント
- `reprodroid-project`: 全体設計、API、ADR、環境構築、作業レポート

全体設計は[ReproDroid設計書](../reprodroid-project/docs/design/Reprodroid%20Document.md)、API契約は[Runner API v1](../reprodroid-project/docs/api/runner-api.md)、実ビルド脅威モデルは[ADR-0003](../reprodroid-project/docs/adr/0003-real-build-security-model.md)を参照してください。

## 技術基盤

| 項目 | 内容 |
|---|---|
| 言語/フレームワーク | Kotlin/JVM + Ktor |
| package/group | `com.sanka1610.reprodroid.runner` |
| JDK | 21 |
| bind先 | `127.0.0.1` |
| port | `8080` |
| 永続化 | SQLite + ファイルストレージ |

## 実行モード

### `SIMULATED`

- Git、Java、Gradle等の外部プロセスを起動しない
- 成功または失敗を再現
- 段階的な進捗、ログ、模擬APKメタデータを返す
- UI、API、永続化、第三者テスト用

### `REAL_TRUSTED`

- デフォルト無効
- Runner起動時の明示設定が必要
- allowlist登録済みGitHub HTTPS URLだけを許可
- リポジトリ別レシピでbuild root、Gradle task、artifact pathを固定
- branch/tagをcommit SHAへ解決し、利用者確認後にdetached checkout
- Wrapperの配布ZIPとJARを公式checksumで検証
- timeout、cancel、監査ログを必須化

Androidから任意コマンドや任意Gradle引数を指定することはできません。

## セキュリティ上の注意

Gradle Wrapperを検証しても、`build.gradle(.kts)`やpluginはホスト上で任意コードを実行できます。allowlistはサンドボックスではありません。

初期の実ビルドは、利用者が選定した信頼済みリポジトリだけをWSL2ホスト上で実行する開発者向けアルファです。未登録の任意リポジトリを安全にビルドできるとは保証しません。

実ビルドには次の二重ゲートを要求します。

1. Runner起動時に`REAL_TRUSTED`を明示的に有効化
2. canonical repository URLがallowlistと一致

さらに、解決済みcommit SHA、実行task、RCE警告をAndroid側で確認するまでbuildを開始しません。

## Wrapper検査

実ビルド前に次を検査します。

- `gradle-wrapper.properties`の構文
- HTTPSの`distributionUrl`と許可ホスト
- Gradle distributionの公式SHA-256
- `gradle-wrapper.jar`の公式SHA-256
- レシピに記載したdistribution version、Wrapper JAR生成version、taskとの整合

対象リポジトリに`distributionSha256Sum`がない場合、レシピが明示的に許可した対象だけ、Runnerが公式checksumを取得して検証します。検査結果は`SUPPLIED_BY_RUNNER`として記録します。公式値で検証できない場合に続行するoverrideは実装しません。

## Jobと永続化

SQLiteへJob、request、resolved commit、recipe ID、variant、Java major、状態、進捗、確認、エラー、ログ索引、artifactメタデータとcontent相対path、distribution/Wrapper検証結果、Manifestの相対pathとSHA-256を保存します。cloneしたソース、配信用に固定コピーしたAPK、ログ本体、Wrapper検査・環境・依存ファイルhashを含む`reprodroid-build.json`は専用state directoryへ保存します。既存schemaは起動時にv4へtransactionalに移行します。Phase 1C以前のcontent pathを持たないartifactは自動推測せず、Jobのretryで再生成します。

Runner再起動時、実行途中だったJobは自動再実行せず`INTERRUPTED`へ移します。

主要な状態:

```text
CREATED
RESOLVING_SOURCE
AWAITING_CONFIRMATION
QUEUED
CLONING
VERIFYING_WRAPPER
BUILDING
DISCOVERING_ARTIFACTS
SUCCEEDED
FAILED
CANCELLED
INTERRUPTED
```

## API

初期APIは次を提供します。

- Job作成・取得
- 実ビルド確認
- ログ差分取得
- cancel・retry
- APK候補一覧
- artifact metadataとAPK content download
- health check

詳細は[Runner API v1](../reprodroid-project/docs/api/runner-api.md)を参照してください。

## 初期テスト対象

主対象は[MorpheApp/MicroG-RE](https://github.com/MorpheApp/MicroG-RE)です。暫定レシピと確認済み構成は[テスト対象文書](../reprodroid-project/docs/test-targets/microg-re.md)に記録しています。

同リポジトリは2026-08-20時点でGradle 8.14.3を使用しますが、`distributionSha256Sum`を記載していません。Runnerによる公式checksum補完を明示的に許可し、検証できなければ拒否します。

## bindと接続

デフォルトは`127.0.0.1:8080`です。

```bash
./gradlew run
```

永続データは既定で`~/.local/state/reprodroid-runner`へ保存します。上書き可能な設定は次のとおりです。

| 環境変数 | 既定値 | 用途 |
|---|---|---|
| `REPRODROID_STATE_DIR` | `~/.local/state/reprodroid-runner` | SQLite、ログ、後続Phaseの成果物 |
| `REPRODROID_HOST` | `127.0.0.1` | bind先 |
| `REPRODROID_PORT` | `8080` | port |
| `REPRODROID_ENABLE_REAL_BUILDS` | `false` | `REAL_TRUSTED`ホスト実行の危険受容 |
| `REPRODROID_JDK_18_HOME` | なし | MicroG-RE `6.1.4` release profile専用JDK 18 |
| `REPRODROID_ALLOW_UNAUTHENTICATED_NON_LOOPBACK` | `false` | 非loopback bindの危険受容 |

非loopbackへbindする場合は`REPRODROID_ALLOW_UNAUTHENTICATED_NON_LOOPBACK=true`による明示的な危険受容が必要です。認証は未実装であるため、通常の利用では指定しないでください。

Androidからは開発用のADB reverseを使用します。

```bash
adb reverse tcp:8080 tcp:8080
```

認証機構を実装するまでは、無認証HTTPをLANへデフォルト公開しません。非loopback bindを用意する場合も、明示設定と警告を必須にします。

## 環境構築

共通スクリプトは次にあります。

```bash
../reprodroid-project/scripts/setup-env.sh
```

JDK 21.0.12.1+1、JDK 18.0.2.1+1、Android SDK API 36、platform-tools、固定したbuild-tools 36.0.0、emulator、system image、SDKライセンスを扱います。JDKはユーザー領域へ導入されるため、実行前にスクリプト末尾の`JAVA_HOME`と`REPRODROID_JDK_18_HOME`を現在のshellへ設定してください。

## ビルド・検証

基本コマンド:

```bash
./gradlew test
./gradlew build
./gradlew run
```

Phase 1Dでは`./gradlew test`と`./gradlew build`で、既存のHTTP API、SQLite、模擬成功・失敗、cancel、安全ゲートに加え、APK content、transfer header、保存後改ざん拒否、schema v3 migrationを検証します。Phase 1E完了時に`./gradlew test build --rerun-tasks`を実行し、8 actionable tasksすべてexecuted、`BUILD SUCCESSFUL`を確認しました。実ビルドを有効化する例:

```bash
REPRODROID_ENABLE_REAL_BUILDS=true \
REPRODROID_JDK_18_HOME="$HOME/.local/share/reprodroid/jdk-18.0.2.1+1" \
./gradlew run
```

起動時設定だけではbuildを開始しません。AndroidまたはAPIから、Runnerが解決したcommit SHAとRCEリスクをJob単位で確認する必要があります。

## Phase 1時点でRunnerが扱わないもの

- allowlist外リポジトリの実ビルド
- Docker等のサンドボックス
- HTTPS/WebSocket
- LANへの無認証デフォルト公開
- 公式APKとの比較（Phase 2ではAndroid側で実施）
- split APK、APKS、AAB
