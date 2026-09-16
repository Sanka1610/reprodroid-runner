# Development

通常利用はRelease ZIPを使用します。この文書はsource checkoutからRunnerを変更・検証する場合の手順です。

## 必要要件

- JDK 21
- Git
- Dockerを使用するtestではDocker Engine

## Buildとtest

```bash
./gradlew test
./gradlew build
```

変更箇所を確認できる最小taskを先に実行します。Docker、実source、device directoryを使うintegration testはtest名とenvironmentを確認して明示的に有効化します。

## Local distribution

```bash
./gradlew installDist
build/install/reprodroid-runner/bin/reprodroid-runner
```

これは開発用のlocal distributionです。公開Release ZIPのchecksum、SBOM、release manifestを置き換えません。

release ZIPとthird-party noticesを検査する場合:

```bash
./gradlew distZip cyclonedxBom verifyReleaseBundle
```

## API文書の更新

route、request/response model、認証、contract header、error code、limitを変更した場合は[API documentation](api/README.md)を同じ変更で更新します。API v1、API v2、pairing v1、capability contract、manifest schema、SQLite schemaは独立したversionとして扱います。
