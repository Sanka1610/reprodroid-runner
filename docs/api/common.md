# Common API rules

## Originと認証

- development: `http://127.0.0.1:8080`またはIPv6 loopback
- paired: `REPRODROID_ADVERTISED_ENDPOINT`で設定したexact HTTPS origin
- redirectは追跡しない
- paired modeの`/v1/*`と`/v2/*`: `Authorization: Bearer <credential>`を1個だけ要求
- development HTTP: 固定`local-development` 接続主体
- `/pairing/v1/*`: [pairing contract](pairing.md)の限定認証を使用

接続主体はサーバー側の認証情報 contextから決定します。要求本文の接続主体 IDをauthorizationに使用しません。

## JSON

次の要求制限はAPI v2の`StrictV2Json.receive`に適用します。pairingは`receivePairing`で64 KiB・深さ16・文字列4 KiBに制限します。v1の受信処理は[Jobs APIの実装参照](jobs-v1.md#実装参照)を参照してください。

- `application/json`, UTF-8
- v2の要求本文は256 KiB以下
- nestingはrootを1として32以下
- 通常文字列はdecoded UTF-8で16 KiB以下
- UUIDはlowercase canonical form
- Git コミットは40桁lowercase SHA-1
- SHA-256は64桁lowercase hex
- UTC 日時はRFC 3339
- duplicate key、unknown 要求フィールド、BOM、不正UTF-8、末尾余分を拒否

manifest、ソーススキャン、APK、bounded ログには各エンドポイント固有の上限があります。

## Mutation header

各APIで指定された更新操作には、対応するcontractと正規形UUIDのidempotency keyを付けます。ツールチェーン計画の解決とself-revokeなど、ヘッダーを要求しない操作は個別の節を参照してください。

```http
X-ReproDroid-Contract: generic-build@1
Idempotency-Key: 00000000-0000-4000-8000-000000000001
```

作成・保存APIは同じ接続主体、操作、key、正規化済み要求に対して既存結果を返し、異なる要求へのkey再利用を`409 IDEMPOTENCY_CONFLICT`として拒否します。ビルドのconfirm／scan-continue／cancelはヘッダー形式を検査し、実行可否を現在のJob状態で判定します。

## Error

```json
{
  "code": "INVALID_REQUEST",
  "message": "The request does not match the contract.",
  "details": {}
}
```

| HTTP | 意味 |
|---|---|
| `400` | JSON、フィールド、ヘッダー、cursor等が不正 |
| `401` | 認証情報がない、不正、失効 |
| `403` | capability/permission不足、RCE確認不足 |
| `404` | リソースなし、または接続主体が所有しない |
| `409` | 状態、ハッシュ、計画、プレビュー、idempotencyの競合 |
| `413` | 本文または専用payload上限超過 |
| `426` | v1 更新操作ではなくv2を使用する必要がある |
| `429` | pairing等のrate 上限 |
| `500` | redacted internal failure |
| `503` | storage/reconciliation等により安全な更新操作を継続できない |

エラーへトークン、秘密情報、private パス、raw tool outputを含めません。

## Health

`GET /v1/health`

```json
{
  "runnerVersion": "0.1.0-alpha02",
  "apiVersion": "v1",
  "realBuildEnabled": true,
  "databaseReady": true
}
```

## Capability

`GET /v2/capabilities`

```json
{
  "apiVersion": "v2",
  "foundationContractVersion": 1,
  "runnerId": "00000000-0000-4000-8000-000000000001",
  "runnerVersion": "0.1.0-alpha02",
  "capabilities": [
    {"id":"foundation","contractVersion":1},
    {"id":"storage-retention","contractVersion":1},
    {"id":"toolchain-install","contractVersion":1}
  ]
}
```

`generic-build`、`apk-comparison`、`codeberg-source`は実ビルドとDockerが有効な場合に広告します。`runner-authentication`はpaired HTTPSの場合に広告します。

## Operation

`GET /v2/operations/{operationId}`は、呼出接続主体が所有する操作を返します。

```json
{
  "operationId": "00000000-0000-4000-8000-000000000010",
  "state": "COMPLETED",
  "kind": "hold-create",
  "requestSha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "result": {"type":"HOLD","resourceId":"00000000-0000-4000-8000-000000000011"},
  "reason": null,
  "createdAt": "2026-09-01T00:00:00Z",
  "updatedAt": "2026-09-01T00:00:01Z"
}
```

操作状態は`RESERVED`、`APPLYING`、`COMPLETED`、`REJECTED`、`RECONCILIATION_REQUIRED`です。

## Self revoke

`POST /v2/authentication/self-revoke`

本文は空JSON object `{}`です。現在のpaired 接続主体を失効させ、`runnerId`、`principalId`、`state`、`revokedAt`を返します。

## 実装参照

- [StrictV2Json.kt](../../src/main/kotlin/com/sanka1610/reprodroid/runner/StrictV2Json.kt) — `StrictV2Json.receive / receivePairing`
- [StorageRetentionApi.kt](../../src/main/kotlin/com/sanka1610/reprodroid/runner/StorageRetentionApi.kt) — `storageRetentionV2Routes`
- [StorageRetentionModels.kt](../../src/main/kotlin/com/sanka1610/reprodroid/runner/StorageRetentionModels.kt) — `V2CapabilitiesResponse / V2OperationResponse`
- [ApiModels.kt](../../src/main/kotlin/com/sanka1610/reprodroid/runner/ApiModels.kt) — `HealthResponse / ApiErrorResponse`
- [PairingApi.kt](../../src/main/kotlin/com/sanka1610/reprodroid/runner/PairingApi.kt) — `requirePrincipal / runnerAuthentication`
