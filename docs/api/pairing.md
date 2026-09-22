# Pairing API v1

pairing routeはpaired HTTPSでだけ公開されます。通常API 認証情報を作成するための限定protocolです。

## Identity

`GET /pairing/v1/identity`

```json
{"schemaVersion":1,"runnerId":"00000000-0000-4000-8000-000000000001"}
```

## Requestを作成する

以下はフィールド構成の例です。invitationの値は`pairing-open`の出力を使用し、端末が生成した認証情報と継続要求用の秘密情報に対応するID・SHA-256を設定します。秘密情報の形式は`RunnerSecurityStore.validatePairingCreate`と`isCanonicalSecret`で検査します。

`POST /pairing/v1/requests`

```json
{
  "schemaVersion": 1,
  "runnerId": "00000000-0000-4000-8000-000000000001",
  "invitationId": "00000000-0000-4000-8000-000000000002",
  "invitationSecret": "<pairing-openで発行された値>",
  "deviceDisplayName": "Android phone",
  "tokenId": "00000000-0000-4000-8000-000000000003",
  "tokenSha256": "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
  "continuationId": "00000000-0000-4000-8000-000000000004",
  "continuationSha256": "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
}
```

Response: `202 Accepted`

```json
{
  "schemaVersion": 1,
  "runnerId": "00000000-0000-4000-8000-000000000001",
  "requestId": "00000000-0000-4000-8000-000000000005",
  "state": "PENDING",
  "expiresAt": "2026-09-01T00:05:00Z",
  "confirmationFingerprint": "display fingerprint",
  "principalId": null
}
```

invitationはPCの`pairing-open`が作成し、5分・1回限りです。ソース別10 request/minute、全体30 request/minuteのrate 上限があります。

## Statusを取得する

継続要求には、作成時の`continuationId`と秘密情報を使います。形式は[RunnerSecurity.kt](../../src/main/kotlin/com/sanka1610/reprodroid/runner/RunnerSecurity.kt)の`parseContinuation`で定義しています。通常API用のBearer認証情報は[共通API](common.md#originと認証)を参照してください。

`GET /pairing/v1/requests/{requestId}`

```http
Authorization: ReproDroid-Continuation <continuationId>.<continuation-secret>
```

応答状態は`PENDING`、`APPROVED`、`REJECTED`、`CANCELLED`、`EXPIRED`です。承認後は`principalId`を返します。AndroidはPC画面と`confirmationFingerprint`を照合してから認証情報を有効化します。

## Cancel

`POST /pairing/v1/requests/{requestId}/cancel`

```http
Authorization: ReproDroid-Continuation <continuationId>.<continuation-secret>
Content-Type: application/json

{}
```

成功時は`204 No Content`です。

malformed UUID、continuation不一致、別要求への秘密情報再利用は認証失敗として処理し、要求の存在を開示しません。

## 実装参照

- [PairingApi.kt](../../src/main/kotlin/com/sanka1610/reprodroid/runner/PairingApi.kt) — `pairingRoutes`
- [RunnerSecurity.kt](../../src/main/kotlin/com/sanka1610/reprodroid/runner/RunnerSecurity.kt) — `RunnerSecurityStore`
