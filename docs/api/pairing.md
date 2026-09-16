# Pairing API v1

pairing routeはpaired HTTPSでだけ公開されます。通常API credentialを作成するための限定protocolです。

## Identity

`GET /pairing/v1/identity`

```json
{"schemaVersion":1,"runnerId":"00000000-0000-4000-8000-000000000001"}
```

## Requestを作成する

`POST /pairing/v1/requests`

```json
{
  "schemaVersion": 1,
  "runnerId": "00000000-0000-4000-8000-000000000001",
  "invitationId": "00000000-0000-4000-8000-000000000002",
  "invitationSecret": "one-time-secret",
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

invitationはPCの`pairing-open`が作成し、5分・1回限りです。source別10 request/minute、全体30 request/minuteのrate limitがあります。

## Statusを取得する

`GET /pairing/v1/requests/{requestId}`

```http
Authorization: Bearer <continuation-secret>
```

response stateは`PENDING`、`APPROVED`、`REJECTED`、`CANCELLED`、`EXPIRED`です。承認後は`principalId`を返します。AndroidはPC画面と`confirmationFingerprint`を照合してからcredentialを有効化します。

## Cancel

`POST /pairing/v1/requests/{requestId}/cancel`

```http
Authorization: Bearer <continuation-secret>
Content-Type: application/json

{}
```

成功時は`204 No Content`です。

malformed UUID、continuation不一致、別requestへのsecret再利用は認証失敗として処理し、requestの存在を開示しません。
