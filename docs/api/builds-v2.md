# Builds and comparison API v2

すべてのendpointは認証済みprincipalを要求します。

## Generic buildを作成する

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
    "configurationSha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    "configurationCanonicalJson": "{...}",
    "expectedArtifactFileName": "app-release.apk",
    "retryOfJobId": null,
    "memoryBytes": 8589934592
  },
  "riskAcknowledged": true
}
```

`configurationCanonicalJson`はbuild root、module、variant、tasks、Java、Gradle、compile SDK、build tools、任意NDK/CMakeを含むstrict configurationです。通常createで`retryOfJobId`または既定外memoryを指定すると拒否します。real buildとDockerが有効でない場合は`GENERIC_DOCKER_REQUIRED`です。

新規requestは`202 Accepted`、同一idempotent requestは`200 OK`でJob receiptを返します。

## Build detailとmutation

| Method | Path | Contract | Body |
|---|---|---|---|
| `GET` | `/v2/builds/{jobId}` | — | — |
| `POST` | `/v2/builds/{jobId}:confirm` | `generic-build@1` | `resolvedCommitSha`, `riskAcknowledged` |
| `POST` | `/v2/builds/{jobId}:scan-continue` | `generic-build@1` | `scanResultSha256`, `riskAcknowledged` |
| `POST` | `/v2/builds/{jobId}:cancel` | `generic-build@1` | `{}` |

detail responseはv1 Job detail、generic build snapshot、任意のdiscovery evidenceを返します。resourceを所有しないprincipalには存在を開示しません。

## Comparisonを作成する

`POST /v2/comparisons`

```http
X-ReproDroid-Contract: apk-comparison@1
Idempotency-Key: 00000000-0000-4000-8000-000000000003
```

```json
{
  "comparisonId": "00000000-0000-4000-8000-000000000004",
  "configurationSha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
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

raw resultは`MATCH`、`DIFFERENT`、`INCOMPARABLE`です。responseの`reproducible`は三軸がすべて`MATCH`で、かつ`trustEligible`と`installEligible`がtrueの場合だけtrueです。

`GET /v2/comparisons/{comparisonId}`は保存済みcomparisonを返します。

## Resource retry

`POST /v2/comparisons/{comparisonId}:retry-resource`

contract headerと新しいidempotency keyを付け、bodyは`{}`です。保存済みcomparisonからmemoryを増やした新しいBuild A/Bを一組だけ作成し、`retryOfComparisonId`を返します。raw mismatch、scan finding、trust/signature failureをresource retryで上書きしません。
