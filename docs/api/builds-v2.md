# Builds and comparison API v2

各エンドポイントは[接続方式に応じたprincipal](common.md#originと認証)で所有権を検査します。

## Generic buildを作成する

以下は要求形式の例です。リポジトリ、コミット、ID、ビルド設定、成果物名を対象に合わせ、設定JSONを正規化してSHA-256を再計算します。

`POST /v2/builds`

```http
X-ReproDroid-Contract: generic-build@1
Idempotency-Key: 00000000-0000-4000-8000-000000000001
```

```json
{
  "repositoryUrl": "https://github.com/example/app.git",
  "commitSha": "0123456789abcdef0123456789abcdef01234567",
  "genericBuild": {
    "comparisonId": "00000000-0000-4000-8000-000000000002",
    "attempt": "A",
    "configurationRevision": 1,
    "configurationSha256": "21103e80658220c6eb540802b5776e5d06f00a5638e4b30b935903063835d191",
    "configurationCanonicalJson": "{\"buildRoot\":\".\",\"buildToolsVersion\":\"36.0.0\",\"cmakeVersion\":null,\"compileSdk\":36,\"gradleVersion\":\"8.14.3\",\"javaMajor\":21,\"modulePath\":\":app\",\"ndkVersion\":null,\"schemaVersion\":1,\"tasks\":[\":app:assembleRelease\"],\"variant\":\"release\"}",
    "expectedArtifactFileName": "app-release.apk",
    "retryOfJobId": null,
    "memoryBytes": 8589934592
  },
  "riskAcknowledged": true
}
```

`configurationCanonicalJson`はビルド root、module、variant、tasks、Java、Gradle、compile SDK、ビルド tools、任意NDK/CMakeを含むstrict 構成です。通常createで`retryOfJobId`または既定外memoryを指定すると拒否します。実ビルドとDockerが有効でない場合は`GENERIC_DOCKER_REQUIRED`です。

新規要求は`202 Accepted`、同一idempotent 要求は`200 OK`でJob receiptを返します。

## Build detailとmutation

| Method | Path | Contract | Body |
|---|---|---|---|
| `GET` | `/v2/builds/{jobId}` | — | — |
| `POST` | `/v2/builds/{jobId}:confirm` | `generic-build@1` | `resolvedCommitSha`, `riskAcknowledged` |
| `POST` | `/v2/builds/{jobId}:scan-continue` | `generic-build@1` | `scanResultSha256`, `riskAcknowledged` |
| `POST` | `/v2/builds/{jobId}:cancel` | `generic-build@1` | `{}` |

detail 応答はv1 Job detail、汎用ビルド snapshot、任意のdiscovery evidenceを返します。リソースを所有しない接続主体には存在を開示しません。

## Comparisonを作成する

`POST /v2/comparisons`

```http
X-ReproDroid-Contract: apk-comparison@1
Idempotency-Key: 00000000-0000-4000-8000-000000000003
```

```json
{
  "comparisonId": "00000000-0000-4000-8000-000000000004",
  "configurationSha256": "21103e80658220c6eb540802b5776e5d06f00a5638e4b30b935903063835d191",
  "officialIdentity": {
    "sha256": "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
    "sizeBytes": 12345678,
    "packageName": "com.example.app",
    "versionName": "1.0",
    "versionCode": 1
  },
  "buildAJobId": "00000000-0000-4000-8000-000000000005",
  "buildBJobId": "00000000-0000-4000-8000-000000000006",
  "officialVsA": "MATCH",
  "officialVsB": "MATCH",
  "buildAVsB": "MATCH",
  "trustEligible": true,
  "installEligible": true
}
```

Androidが計算したraw結果と適格性を要求に含めます。Runnerは`SQLiteJobStore.createGenericComparison`で所有権、A／Bの役割、比較ID、構成ハッシュと正規化済みJSON、Job状態を検査して保存します。応答の`reproducible`は、両Jobが`SUCCEEDED`、3軸すべてが`MATCH`、要求の`trustEligible`と`installEligible`がともに`true`の場合に`true`です。

`GET /v2/comparisons/{comparisonId}`は保存済みcomparisonを返します。

## Resource retry

`POST /v2/comparisons/{comparisonId}:retry-resource`

contract ヘッダーと新しいidempotency keyを付け、本文は`{}`です。保存済みcomparisonからmemoryを増やした新しいBuild A/Bを一組だけ作成し、`retryOfComparisonId`を返します。raw mismatch、scan finding、trust/signature failureをリソース再試行で上書きしません。

## 実装参照

- [GenericBuildApi.kt](../../src/main/kotlin/com/sanka1610/reprodroid/runner/GenericBuildApi.kt) — `genericBuildV2Routes / GenericBuildCreateBody`
- [GenericBuild.kt](../../src/main/kotlin/com/sanka1610/reprodroid/runner/GenericBuild.kt) — `GenericBuildConfiguration / GenericBuildSnapshot / GenericBuildContract / CreateGenericComparisonRequest`

保存条件と応答の計算は[SQLiteJobStore.kt](../../src/main/kotlin/com/sanka1610/reprodroid/runner/SQLiteJobStore.kt)の`createGenericComparison`、メモリ既定値は[BuildSandbox.kt](../../src/main/kotlin/com/sanka1610/reprodroid/runner/BuildSandbox.kt)の`GenericDockerSandboxProfile.DEFAULT_MEMORY_BYTES`を参照してください。
