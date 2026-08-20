# ReproDroid Runner

ReproDroid AndroidアプリからJobを受け、模擬ビルドまたはallowlist登録済みAndroid OSSの実ビルドを実行するPC側常駐プロセスです。

## 現在の状態

Phase 1A（管理基盤）まで実装済みです。

- Kotlin/JVM + KtorのGradleプロジェクトとVersion Catalog
- 公式SHA-256を固定したGradle Wrapper 8.14.3
- SQLite/Ktor/serialization依存の固定
- mainクラスのcompile・起動確認

Phase 1Aのmainはscaffold状態を表示して終了します。HTTP API、SQLite、Job executor、`SIMULATED`、`REAL_TRUSTED`は未実装で、Phase 1B以降に追加します。

初期実装では、Jobの永続化、模擬成功・失敗、信頼済みGit clone、Gradle Wrapper検査、実ビルド、APK検出・配信までを実装します。

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
- レシピに記載したGradle version/taskとの整合

対象リポジトリに`distributionSha256Sum`がない場合、レシピが明示的に許可した対象だけ、Runnerが公式checksumを取得して検証します。検査結果は`SUPPLIED_BY_RUNNER`として記録します。公式値で検証できない場合に続行するoverrideは実装しません。

## Jobと永続化

SQLiteへJob、request、resolved commit、状態、進捗、確認、エラー、ログ索引、artifactメタデータ、checksum、Wrapper検査結果を保存します。APK、cloneしたソース、ログ本体は専用state directoryへ保存します。

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
- artifact download
- health check

詳細は[Runner API v1](../reprodroid-project/docs/api/runner-api.md)を参照してください。

## 初期テスト対象

主対象は[MorpheApp/MicroG-RE](https://github.com/MorpheApp/MicroG-RE)です。暫定レシピと確認済み構成は[テスト対象文書](../reprodroid-project/docs/test-targets/microg-re.md)に記録しています。

同リポジトリは2026-08-20時点でGradle 8.14.3を使用しますが、`distributionSha256Sum`を記載していません。Runnerによる公式checksum補完を明示的に許可し、検証できなければ拒否します。

## bindと接続

以下はPhase 1B以降の予定です。Phase 1AではHTTP listenerを起動しません。

デフォルトは`127.0.0.1:8080`です。

```bash
./gradlew run
```

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

Phase 1Aでは`./gradlew build run`が成功し、scaffold mainの起動を確認済みです。HTTPサーバーの起動手順はPhase 1Bで更新します。

## 初期実装で扱わないもの

- allowlist外リポジトリの実ビルド
- Docker等のサンドボックス
- HTTPS/WebSocket
- LANへの無認証デフォルト公開
- 公式APKとの比較
- split APK、APKS、AAB
