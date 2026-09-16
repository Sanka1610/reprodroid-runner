# Runner API

Runner `0.1.0-alpha02`が提供するHTTP APIです。

| Family | Base path | 用途 |
|---|---|---|
| [Common](common.md) | `/v1`, `/v2` | transport、認証、JSON、error、capability |
| [Pairing](pairing.md) | `/pairing/v1` | manual pairing requestとstatus |
| [Jobs v1](jobs-v1.md) | `/v1` | legacy/fixed recipe Job、log、artifact、scan |
| [Builds v2](builds-v2.md) | `/v2` | generic build、raw comparison、resource retry |
| [Toolchains v2](toolchains-v2.md) | `/v2/toolchains` | catalog、plan、install、inventory、removal |
| [Storage v2](storage-v2.md) | `/v2` | operation、hold、reservation、cleanup |

API family、Runner product version、capability contract、pairing protocol、manifest schema、SQLite schemaは独立したversionです。

paired HTTPSでは通常のv1/v2 requestに端末別bearer credentialが必要です。development HTTPではexact loopbackの固定local principalを使います。pairing endpointは期限付きinvitationの範囲だけを扱います。
