# Toolchain API v2

Base pathは`/v2/toolchains`、capabilityは`toolchain-install@1`です。

## Endpoint

| Method | Path | Mutation headers | 用途 |
|---|---|---|---|
| `GET` | `/v2/toolchains/catalog` | — | platform、artifact、license、digest、limit |
| `GET` | `/v2/toolchains/licenses/{licenseId}` | — | license metadata/text |
| `POST` | `/v2/toolchains/plans:resolve` | — | requirementsからplanを解決 |
| `GET` | `/v2/toolchains/inventory` | — | atomic publish済みtoolchain一覧 |
| `POST` | `/v2/toolchains/installations` | required | planとlicense acceptanceを実行 |
| `GET` | `/v2/toolchains/installations/{installationId}` | — | state/progress/item/error |
| `POST` | `/v2/toolchains/installations/{installationId}:cancel` | required | cancel request |
| `POST` | `/v2/toolchains/removals:preview` | required | removal対象とblockerを確認 |
| `POST` | `/v2/toolchains/removals:execute` | required | staleを再検証して削除 |

mutation header:

```http
X-ReproDroid-Contract: toolchain-install@1
Idempotency-Key: 00000000-0000-4000-8000-000000000001
```

## Plan

```json
{
  "requirements": [
    {"component":"JDK","version":"21.0.12+1"},
    {"component":"GRADLE","version":"8.14.3"},
    {"component":"ANDROID_PLATFORM","version":"36-r02"},
    {"component":"ANDROID_BUILD_TOOLS","version":"36.0.0"}
  ]
}
```

responseは`runnerId`、`catalogSha256`、`planSha256`、`platform`、artifact items、必要license、download/reservation bytesを返します。

## Installation

installation requestはplanで使用したrequirements、`catalogSha256`、`planSha256`、必要licenseごとの`licenseId`、`licenseTextSha256`、`accepted: true`を含みます。catalog/planが変化した場合は`409 TOOLCHAIN_PLAN_STALE`、現在のlicense同意がない場合は`409 LICENSE_ACCEPTANCE_REQUIRED`です。

state:

```text
PLANNED
AWAITING_LICENSE
RESERVING
DOWNLOADING
VERIFYING_ARCHIVE
EXTRACTING
VERIFYING_CONTENT
PUBLISHING
INSTALLED
CANCEL_REQUESTED
CANCELLED
FAILED
RECONCILIATION_REQUIRED
```

`INSTALLED`はarchive、size、path、content manifest、atomic publish、inventory保存が完了した状態です。build成功を意味しません。

## Removal

previewはinventory itemと利用中/blockerを返します。execute requestはpreview IDとpreview digestを含み、期限、inventory、参照、ownershipを再検証します。stale previewは`409 TOOLCHAIN_REMOVAL_STALE`です。
