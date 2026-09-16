# Common API rules

## Originと認証

- development: `http://127.0.0.1:8080`またはIPv6 loopback
- paired: `REPRODROID_ADVERTISED_ENDPOINT`で設定したexact HTTPS origin
- redirectは追跡しない
- paired modeの`/v1/*`と`/v2/*`: `Authorization: Bearer <credential>`を1個だけ要求
- development HTTP: 固定`local-development` principal
- `/pairing/v1/*`: [pairing contract](pairing.md)の限定認証を使用

principalはserver側のcredential contextから決定します。request bodyのprincipal IDをauthorizationに使用しません。

## JSON

- `application/json`, UTF-8
- 通常requestは256 KiB以下、通常responseは1 MiB以下
- nestingはrootを1として32以下
- 通常stringはdecoded UTF-8で16 KiB以下
- UUIDはlowercase canonical form
- Git commitは40桁lowercase SHA-1
- SHA-256は64桁lowercase hex
- UTC timestampはRFC 3339
- duplicate key、unknown request field、BOM、不正UTF-8、末尾余分を拒否

manifest、source scan、APK、bounded logには各endpoint固有の上限があります。

## Mutation header

API v2のmutationはendpointごとのcontractとcanonical UUIDのidempotency keyを要求します。

```http
X-ReproDroid-Contract: generic-build@1
Idempotency-Key: 00000000-0000-4000-8000-000000000001
```

同じprincipal、operation、key、同じcanonical requestは既存結果を返します。同じkeyを異なるrequestへ再利用すると`409 IDEMPOTENCY_CONFLICT`です。

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
| `400` | JSON、field、header、cursor等が不正 |
| `401` | credentialがない、不正、失効 |
| `403` | capability/permission不足、RCE確認不足 |
| `404` | resourceなし、またはprincipalが所有しない |
| `409` | state、digest、plan、preview、idempotencyの競合 |
| `413` | bodyまたは専用payload上限超過 |
| `426` | v1 mutationではなくv2を使用する必要がある |
| `429` | pairing等のrate limit |
| `500` | redacted internal failure |
| `503` | storage/reconciliation等により安全なmutationを継続できない |

errorへtoken、secret、private path、raw tool outputを含めません。

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

`generic-build`、`apk-comparison`、`codeberg-source`はreal buildとDockerが有効な場合に広告します。`runner-authentication`はpaired HTTPSの場合に広告します。

## Operation

`GET /v2/operations/{operationId}`は、呼出principalが所有するoperationを返します。

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

operation stateは`RESERVED`、`APPLYING`、`COMPLETED`、`REJECTED`、`RECONCILIATION_REQUIRED`です。

## Self revoke

`POST /v2/authentication/self-revoke`

bodyは空JSON object `{}`です。現在のpaired principalを失効させ、`runnerId`、`principalId`、`state`、`revokedAt`を返します。
