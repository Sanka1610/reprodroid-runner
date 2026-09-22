# Runner API

このチェックアウトのHTTP APIを説明します。対象Runnerの版と機能は[health／capability](common.md#health)で確認してください。要求例のID、ハッシュ、日時、リポジトリは説明用です。

| Family | Base パス | 用途 |
|---|---|---|
| [Common](common.md) | `/v1`, `/v2` | transport、認証、JSON、エラー、capability |
| [Pairing](pairing.md) | `/pairing/v1` | 手動ペアリング要求とstatus |
| [Jobs v1](jobs-v1.md) | `/v1` | legacy/fixed recipe Job、ログ、成果物、scan |
| [Builds v2](builds-v2.md) | `/v2` | 汎用ビルド、raw比較、リソース再試行 |
| [Toolchains v2](toolchains-v2.md) | `/v2/toolchains` | カタログ、計画、インストール、inventory、removal |
| [Storage v2](storage-v2.md) | `/v2` | 操作、hold、reservation、cleanup |

API family、Runner product バージョン、capability contract、pairing protocol、manifest スキーマ、SQLite スキーマは独立したバージョンです。

paired HTTPSでは通常のv1/v2 要求に端末別bearer 認証情報が必要です。development HTTPではexact loopbackの固定local 接続主体を使います。pairing エンドポイントは期限付きinvitationの範囲だけを扱います。
