# Storage and retention API v2

capabilityは`storage-retention@1`です。mutationは次のheaderを要求します。

```http
X-ReproDroid-Contract: storage-retention@1
Idempotency-Key: 00000000-0000-4000-8000-000000000001
```

## Storage summary

`GET /v2/storage/summary`

Runner Jobとmanaged toolchainのareaごとにbudget、used、reserved、unclassified、filesystem usable bytes、warning percent、measurement stateを返します。filesystem測定に失敗したareaを空き容量ありとして扱いません。

## Hold

`POST /v2/retention/holds`

```json
{
  "resource": {"kind":"JOB","id":"00000000-0000-4000-8000-000000000002"},
  "reason": "CURRENT_COMPARISON",
  "clientReference": {"type":"COMPARISON","id":"00000000-0000-4000-8000-000000000003"}
}
```

resource kindは`JOB`または`ARTIFACT`、create reasonは`CURRENT_COMPARISON`、`CURRENT_RELEASE`、`EXPORT_SNAPSHOT`です。呼出principalが所有する既存resourceだけをholdできます。

`POST /v2/retention/holds/{holdId}/release` body:

```json
{"reason":"REFERENCE_RELEASED"}
```

release reasonは`REFERENCE_RELEASED`、`APP_REMOVED`、`COMPARISON_REPLACED`です。

## Reservation

`POST /v2/storage/reservations`はarea、purpose、resource、requested bytesを受け、operation receiptを返します。budget、filesystem usable space、既存reservationを再計算します。

`POST /v2/storage/reservations/{reservationId}/release`は`OPERATION_CANCELLED`、`OPERATION_COMPLETED`、`RESERVATION_NOT_NEEDED`のreasonを受けます。

## Cleanup

| Method | Path | 用途 |
|---|---|---|
| `POST` | `/v2/cleanup/previews` | area、resource kind、cutoff、任意IDから候補作成 |
| `GET` | `/v2/cleanup/previews/{previewId}` | 候補、bytes、blocker、期限を取得 |
| `POST` | `/v2/cleanup/previews/{previewId}/execute` | preview digestを指定して実行 |
| `GET` | `/v2/cleanup/runs/{cleanupRunId}` | item別結果と解放量を取得 |

execute時にresource、hold、ownership、参照、preview期限を再検証します。stale previewを部分的な成功へ変換しません。itemごとの削除失敗はrun detailに保持します。

## Operation receipt

新しいmutationは通常`202 Accepted`、同一idempotent requestは`200 OK`です。responseの`operationId`を`GET /v2/operations/{operationId}`で追跡します。
