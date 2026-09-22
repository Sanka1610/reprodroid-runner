# Jobs API v1

Base パスは`/v1`です。API v2が有効なRunnerでは、新しいv1 execution 更新操作は`426 API_UPGRADE_REQUIRED`になります。health、Job detail、ログ、成果物等の取得操作は利用できます。

## Endpoint

| Method | Path | Request | Response |
|---|---|---|---|
| `GET` | `/v1/health` | — | バージョン、実ビルド gate、database readiness |
| `POST` | `/v1/jobs` | `CreateJobRequest` | `202` Job ID/state |
| `GET` | `/v1/jobs/{jobId}` | — | Job detail |
| `POST` | `/v1/jobs/{jobId}/confirm` | コミットとRCE確認 | `204` |
| `POST` | `/v1/jobs/{jobId}/cancel` | — | `204` |
| `POST` | `/v1/jobs/{jobId}/retry` | — | `202` 新Job |
| `GET` | `/v1/jobs/{jobId}/logs` | cursor query | bounded ログ page |
| `GET` | `/v1/jobs/{jobId}/artifacts` | — | 成果物 metadata |
| `GET` | `/v1/jobs/{jobId}/artifacts/{artifactId}/content` | — | APK バイト列 |
| `GET` | `/v1/jobs/{jobId}/build-environment-manifest` | — | redacted manifest |
| `GET` | `/v1/jobs/{jobId}/source-scan` | — | bounded scan detail |
| `POST` | `/v1/jobs/{jobId}/source-scan/continue` | scan ハッシュと確認 | `204` |

## Jobを作成する

```json
{
  "executionMode": "REAL_TRUSTED",
  "repositoryUrl": "https://github.com/example/app.git",
  "revision": {"type":"COMMIT","value":"0123456789abcdef0123456789abcdef01234567"},
  "simulationOutcome": null
}
```

`executionMode`は`SIMULATED`または`REAL_TRUSTED`、revision typeは`BRANCH`、`TAG`、`COMMIT`です。実ビルドはRunner内のallowlist/fixed recipe、`REPRODROID_ENABLE_REAL_BUILDS=true`、解決済みコミットの確認を要求します。Gradle task、argument、成果物パス、Java runtimeを要求から指定できません。

```json
{"jobId":"00000000-0000-4000-8000-000000000001","state":"CREATED"}
```

## Job state

`CREATED`、`RESOLVING_SOURCE`、`AWAITING_CONFIRMATION`、`QUEUED`、`CLONING`、`SCANNING_SOURCE`、`AWAITING_SCAN_REVIEW`、`VERIFYING_WRAPPER`、`DISCOVERING_CONFIGURATION`、`BUILDING`、`DISCOVERING_ARTIFACTS`、`SUCCEEDED`、`FAILED`、`CANCELLED`、`INTERRUPTED`を使用します。

Job detailは要求、resolved コミット、state/progress、effective ビルド、ログ cursor、成果物、エラー、日時を返します。未知状態を成功として扱いません。

## Confirm

```json
{
  "resolvedCommitSha": "0123456789abcdef0123456789abcdef01234567",
  "riskAcknowledged": true
}
```

現在保存されているコミットと完全一致し、`riskAcknowledged`がtrueの場合だけキューへ進みます。再試行は新Jobを作り、ref解決と確認をやり直します。

## Source scan continue

```json
{
  "scanResultSha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "riskAcknowledged": true
}
```

保存されたscan ハッシュと一致するfindingだけを確認します。

## Logs

`GET /v1/jobs/{jobId}/logs?afterSequence=0&limit=200`

`afterSequence`は0以上、`limit`は1..500です。応答は`entries`、`nextAfterSequence`、`hasMore`を返します。

## Artifact

成果物 listは`artifactId`、`fileName`、`sizeBytes`、`sha256`等を返します。content 応答は次を返します。

- `Content-Type: application/vnd.android.package-archive`
- `Content-Length`
- SHA-256を引用符で囲んだ`ETag`

Runnerはダウンロード直前に登録済みsize/digestと実ファイルを照合します。Job未完了、contentなし、path/symlink/digest不一致は`409`です。

## Build Environment Manifest

private manifestを直接返さず、パス、利用者、秘密情報を除去したpublic projectionを返します。コミット、Java、Gradle、Android SDK/build tools、dependency ハッシュ、APK ハッシュ、determinism、scan、sandbox identityを含みます。Androidはmanifest単独をtrustまたはreproducibility判定に使用しません。

## 実装参照

- [RunnerApi.kt](../../src/main/kotlin/com/sanka1610/reprodroid/runner/RunnerApi.kt) — `runnerModule`
- [ApiModels.kt](../../src/main/kotlin/com/sanka1610/reprodroid/runner/ApiModels.kt) — `CreateJobRequest / JobState / JobResponse / LogResponse`
