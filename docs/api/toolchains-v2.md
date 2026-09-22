# Toolchain API v2

Base パスは`/v2/toolchains`、capabilityは`toolchain-install@1`です。

## Endpoint

| Method | Path | Mutation headers | 用途 |
|---|---|---|---|
| `GET` | `/v2/toolchains/catalog` | — | platform、成果物、ライセンス、ハッシュ、上限 |
| `GET` | `/v2/toolchains/licenses/{licenseId}` | — | ライセンス metadata/text |
| `POST` | `/v2/toolchains/plans:resolve` | — | requirementsから計画を解決 |
| `GET` | `/v2/toolchains/inventory` | — | atomic publish済みツールチェーン一覧 |
| `POST` | `/v2/toolchains/installations` | required | 計画とライセンス acceptanceを実行 |
| `GET` | `/v2/toolchains/installations/{installationId}` | — | state/progress/item/error |
| `POST` | `/v2/toolchains/installations/{installationId}:cancel` | required | cancel 要求 |
| `POST` | `/v2/toolchains/removals:preview` | required | removal対象とblockerを確認 |
| `POST` | `/v2/toolchains/removals:execute` | required | 期限切れまたは内容変更済みのを再検証して削除 |

更新操作ヘッダー:

```http
X-ReproDroid-Contract: toolchain-install@1
Idempotency-Key: 00000000-0000-4000-8000-000000000001
```

## Plan

以下は要求形式の例です。`component`と`version`は稼働中Runnerのカタログから選択してください。

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

応答は`runnerId`、`catalogSha256`、`planSha256`、`platform`、成果物 items、必要ライセンス、download/reservation バイト列を返します。

## Installation

installation 要求は計画で使用したrequirements、`catalogSha256`、`planSha256`、必要ライセンスごとの`licenseId`、`licenseTextSha256`、`accepted: true`を含みます。catalog/planが変化した場合は`409 TOOLCHAIN_PLAN_STALE`、現在のライセンス同意がない場合は`409 LICENSE_ACCEPTANCE_REQUIRED`です。

状態:

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

`INSTALLED`はarchive、サイズ、パス、content manifest、atomic publish、inventory保存が完了した状態です。ビルドの実行結果はJobで確認します。

## Removal

プレビューはinventory itemと利用中/blockerを返します。execute 要求は`previewId`を含み、期限、inventory、参照、所有権を再検証します。期限切れまたは内容変更済みのプレビューは`409 TOOLCHAIN_REMOVAL_STALE`です。

## 実装参照

- [ToolchainApi.kt](../../src/main/kotlin/com/sanka1610/reprodroid/runner/ToolchainApi.kt) — `toolchainV2Routes`
- [ToolchainModels.kt](../../src/main/kotlin/com/sanka1610/reprodroid/runner/ToolchainModels.kt) — `ResolveToolchainPlanRequest / CreateToolchainInstallationRequest / ExecuteToolchainRemovalRequest / ToolchainInstallationState`
