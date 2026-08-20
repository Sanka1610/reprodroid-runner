# ReproDroid Runner

ReproDroid AndroidアプリからJobを受け、模擬ビルドまたはallowlist登録済みAndroid OSSの実ビルドを実行するPC側常駐プロセスです。

## 現在の状態

Phase 1D（APK転送）まで実装済みで、Phase 1Eは検証途中です。

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

Phase 1EではMicroG-REの固定taskをRunner APIとAndroid UIからそれぞれ実行し、同一commitから同一size・SHA-256のAPKを生成した。content endpointからの取得と、同じstate directoryでのRunner再起動後にJob、artifact、log cursorが復元されることも確認済みである。Android側download以降のインストールE2Eは未確認。詳細は[Phase 1E検証レポート](../reprodroid-project/reports/2026/08/2026-08-21-phase-1e.md)、保持中のstateと再起動コマンドは[Phase 1E再開手順](../reprodroid-project/docs/handoffs/phase-1e-resume.md)を参照してください。

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

SQLiteへJob、request、resolved commit、状態、進捗、確認、エラー、ログ索引、artifactメタデータとcontent相対path、distribution/Wrapper検証結果、Manifestの相対pathとSHA-256を保存します。cloneしたソース、配信用に固定コピーしたAPK、ログ本体、Wrapper検査・環境・依存ファイルhashを含む`reprodroid-build.json`は専用state directoryへ保存します。既存schemaは起動時にv3へtransactionalに移行します。Phase 1C以前のcontent pathを持たないartifactは自動推測せず、Jobのretryで再生成します。

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

JDK 21.0.12.1+1、Android SDK API 36、platform-tools、固定したbuild-tools 36.0.0、emulator、system image、SDKライセンスを扱います。JDKはユーザー領域へ導入されるため、実行前にスクリプト末尾の`JAVA_HOME`を現在のshellへ設定してください。

## ビルド・検証

基本コマンド:

```bash
./gradlew test
./gradlew build
./gradlew run
```

Phase 1Dでは`./gradlew test`と`./gradlew build`で、既存のHTTP API、SQLite、模擬成功・失敗、cancel、安全ゲートに加え、APK content、transfer header、保存後改ざん拒否、schema v3 migrationを検証します。Phase 1E中断時点では`./gradlew test build`が成功したが、8 tasksすべてが`UP-TO-DATE`であり、`--rerun-tasks`による再実行はしていません。実ビルドを有効化する例:

```bash
REPRODROID_ENABLE_REAL_BUILDS=true ./gradlew run
```

起動時設定だけではbuildを開始しません。AndroidまたはAPIから、Runnerが解決したcommit SHAとRCEリスクをJob単位で確認する必要があります。

## 初期実装で扱わないもの

- allowlist外リポジトリの実ビルド
- Docker等のサンドボックス
- HTTPS/WebSocket
- LANへの無認証デフォルト公開
- 公式APKとの比較
- split APK、APKS、AAB
