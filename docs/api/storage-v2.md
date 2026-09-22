# Storage and retention API v2

capabilityは`storage-retention@1`です。更新操作は次のヘッダーを要求します。

```http
X-ReproDroid-Contract: storage-retention@1
Idempotency-Key: 00000000-0000-4000-8000-000000000001
```

## Storage summary

`GET /v2/storage/summary`

Runner Jobとmanaged ツールチェーンのareaごとにbudget、used、reserved、unclassified、filesystem usable バイト列、warning percent、measurement 状態を返します。filesystem測定に失敗したareaを空き容量ありとして扱いません。

## Hold

`POST /v2/retention/holds`

```json
{
  "resource": {"kind":"JOB","id":"00000000-0000-4000-8000-000000000002"},
  "reason": "CURRENT_COMPARISON",
  "clientReference": {"type":"COMPARISON","id":"00000000-0000-4000-8000-000000000003"}
}
```

リソース kindは`JOB`または`ARTIFACT`、create reasonは`CURRENT_COMPARISON`、`CURRENT_RELEASE`、`EXPORT_SNAPSHOT`です。呼出接続主体が所有する既存リソースだけをholdできます。

`POST /v2/retention/holds/{holdId}/release` 本文:

```json
{"reason":"REFERENCE_RELEASED"}
```

リリース reasonは`REFERENCE_RELEASED`、`APP_REMOVED`、`COMPARISON_REPLACED`です。

## Reservation

`POST /v2/storage/reservations`はarea、purpose、リソース、requested バイト列を受け、操作 receiptを返します。budget、filesystem usable space、既存reservationを再計算します。

`POST /v2/storage/reservations/{reservationId}/release`は`OPERATION_CANCELLED`、`OPERATION_COMPLETED`、`RESERVATION_NOT_NEEDED`のreasonを受けます。

## Cleanup

| Method | Path | 用途 |
|---|---|---|
| `POST` | `/v2/cleanup/previews` | area、リソース kind、cutoff、任意IDから候補作成 |
| `GET` | `/v2/cleanup/previews/{previewId}` | 候補、バイト列、blocker、期限を取得 |
| `POST` | `/v2/cleanup/previews/{previewId}/execute` | プレビュー ハッシュを指定して実行 |
| `GET` | `/v2/cleanup/runs/{cleanupRunId}` | item別結果と解放量を取得 |

execute時にリソース、hold、所有権、参照、プレビュー期限を再検証します。期限切れまたは内容変更済みのプレビューを部分的な成功へ変換しません。itemごとの削除失敗はrun detailに保持します。

## Operation receipt

新しい更新操作は通常`202 Accepted`、同一idempotent 要求は`200 OK`です。応答の`operationId`を`GET /v2/operations/{operationId}`で追跡します。

## 実装参照

- [StorageRetentionApi.kt](../../src/main/kotlin/com/sanka1610/reprodroid/runner/StorageRetentionApi.kt) — `storageRetentionV2Routes / parseHoldCreate / parseReservationCreate / parseCleanupPreview / parseCleanupExecute`
- [StorageRetentionModels.kt](../../src/main/kotlin/com/sanka1610/reprodroid/runner/StorageRetentionModels.kt) — `StorageAreaSummary / CleanupPreviewResponse / CleanupRunResponse`
