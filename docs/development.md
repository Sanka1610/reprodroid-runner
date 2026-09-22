# Development

通常利用はRelease ZIPを使用します。この文書はソースのチェックアウトからRunnerを変更・検証する場合の手順です。

## 必要要件

- JDK 21
- Git
- Dockerを使用するテストではDocker Engine

## Buildとtest

```bash
./gradlew test
./gradlew build
```

変更箇所を確認できる最小タスクを先に実行します。Docker、実ソース、device ディレクトリを使うintegration テストはテスト名と環境変数を確認して明示的に有効化します。

## Local distribution

```bash
./gradlew installDist
build/install/reprodroid-runner/bin/reprodroid-runner
```

生成先は`build/install/reprodroid-runner`です。公開用のZIPは次のタスクで作成します。

リリース ZIPとthird-party noticesを検査する場合:

```bash
./gradlew distZip cyclonedxBom verifyReleaseBundle
```

## API文書の更新

route、request/response model、認証、contract ヘッダー、エラー code、上限を変更した場合は[API documentation](api/README.md)を同じ変更で更新します。API v1、API v2、pairing v1、capability contract、manifest スキーマ、SQLite スキーマは独立したバージョンとして扱います。
