# Jobs API v1

Base pathは`/v1`です。API v2が有効なRunnerでは、新しいv1 execution mutationは`426 API_UPGRADE_REQUIRED`になります。health、Job detail、log、artifact等のreadは利用できます。

## Endpoint

| Method | Path | Request | Response |
|---|---|---|---|
| `GET` | `/v1/health` | — | version、real build gate、database readiness |
| `POST` | `/v1/jobs` | `CreateJobRequest` | `202` Job ID/state |
| `GET` | `/v1/jobs/{jobId}` | — | Job detail |
| `POST` | `/v1/jobs/{jobId}/confirm` | commitとRCE確認 | `204` |
| `POST` | `/v1/jobs/{jobId}/cancel` | — | `204` |
| `POST` | `/v1/jobs/{jobId}/retry` | — | `202` 新Job |
| `GET` | `/v1/jobs/{jobId}/logs` | cursor query | bounded log page |
| `GET` | `/v1/jobs/{jobId}/artifacts` | — | artifact metadata |
| `GET` | `/v1/jobs/{jobId}/artifacts/{artifactId}/content` | — | APK bytes |
| `GET` | `/v1/jobs/{jobId}/build-environment-manifest` | — | redacted manifest |
| `GET` | `/v1/jobs/{jobId}/source-scan` | — | bounded scan detail |
| `POST` | `/v1/jobs/{jobId}/source-scan/continue` | scan digestと確認 | `204` |

## Jobを作成する

```json
{
  "executionMode": "REAL_TRUSTED",
  "repositoryUrl": "https://github.com/example/app.git",
  "revision": {"type":"COMMIT","value":"0123456789abcdef0123456789abcdef01234567"},
  "simulationOutcome": null
}
```

`executionMode`は`SIMULATED`または`REAL_TRUSTED`、revision typeは`BRANCH`、`TAG`、`COMMIT`です。実buildはRunner内のallowlist/fixed recipe、`REPRODROID_ENABLE_REAL_BUILDS=true`、解決済みcommitの確認を要求します。Gradle task、argument、artifact path、Java runtimeをrequestから指定できません。

```json
{"jobId":"00000000-0000-4000-8000-000000000001","state":"CREATED"}
```

## Job state

`CREATED`、`RESOLVING_SOURCE`、`AWAITING_CONFIRMATION`、`QUEUED`、`CLONING`、`SCANNING_SOURCE`、`AWAITING_SCAN_REVIEW`、`VERIFYING_WRAPPER`、`DISCOVERING_CONFIGURATION`、`BUILDING`、`DISCOVERING_ARTIFACTS`、`SUCCEEDED`、`FAILED`、`CANCELLED`、`INTERRUPTED`を使用します。

Job detailはrequest、resolved commit、state/progress、effective build、log cursor、artifact、error、timestampを返します。未知stateを成功として扱いません。

## Confirm

```json
{
  "resolvedCommitSha": "0123456789abcdef0123456789abcdef01234567",
  "riskAcknowledged": true
}
```

現在保存されているcommitと完全一致し、`riskAcknowledged`がtrueの場合だけqueueへ進みます。retryは新Jobを作り、ref解決と確認をやり直します。

## Source scan continue

```json
{
  "scanResultSha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "riskAcknowledged": true
}
```

保存されたscan digestと一致するfindingだけを確認します。

## Logs

`GET /v1/jobs/{jobId}/logs?afterSequence=0&limit=200`

`afterSequence`は0以上、`limit`は1..500です。responseは`entries`、`nextAfterSequence`、`hasMore`を返します。

## Artifact

artifact listは`artifactId`、`fileName`、`sizeBytes`、`sha256`等を返します。content responseは次を返します。

- `Content-Type: application/vnd.android.package-archive`
- `Content-Length`
- SHA-256を引用符で囲んだ`ETag`

Runnerはdownload直前に登録済みsize/digestと実fileを照合します。Job未完了、contentなし、path/symlink/digest不一致は`409`です。

## Build Environment Manifest

private manifestを直接返さず、path、user、secretを除去したpublic projectionを返します。commit、Java、Gradle、Android SDK/build tools、dependency digest、APK digest、determinism、scan、sandbox identityを含みます。Androidはmanifest単独をtrustまたはreproducibility判定に使用しません。
