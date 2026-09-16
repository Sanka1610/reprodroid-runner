# Configuration

Runnerは起動時に環境変数を読み取り、不正値や危険な組合せをfail closedで拒否します。

| Variable | Default | 現在の動作 |
|---|---|---|
| `REPRODROID_STATE_DIR` | `~/.local/state/reprodroid-runner` | SQLite、security state、log、artifactを保存。空値を拒否 |
| `REPRODROID_TRANSPORT_MODE` | `DEVELOPMENT_HTTP` | `DEVELOPMENT_HTTP`または`PAIRED_HTTPS`のみ |
| `REPRODROID_HOST` | `127.0.0.1` | development HTTPは`127.0.0.1`または`::1`のみ。paired HTTPSはwildcardを拒否 |
| `REPRODROID_PORT` | HTTP `8080`、HTTPS `8443` | `1..65535`の整数 |
| `REPRODROID_ADVERTISED_ENDPOINT` | unset | paired HTTPSで必須。設定portを持つpath/query/fragmentなしのexact HTTPS origin |
| `REPRODROID_ENABLE_REAL_BUILDS` | `false` | `true`の場合だけ実source buildを許可 |
| `REPRODROID_ENABLE_API_V2` | `false` | loopback developmentでv2を有効化。paired HTTPSでは常に有効 |
| `REPRODROID_BUILD_SANDBOX` | `HOST` | `HOST`または`DOCKER`。generic buildは`DOCKER`を要求 |
| `REPRODROID_JDK_18_HOME` | unset | 固定MicroG-RE recipeで使用する検査可能なJDK 18 |

旧`REPRODROID_ALLOW_UNAUTHENTICATED_NON_LOOPBACK=true`は起動時に拒否します。

## Paired HTTPS例

```bash
export REPRODROID_STATE_DIR="$HOME/.local/state/reprodroid-runner"
export REPRODROID_TRANSPORT_MODE=PAIRED_HTTPS
export REPRODROID_HOST=192.0.2.10
export REPRODROID_PORT=8443
export REPRODROID_ADVERTISED_ENDPOINT=https://192.0.2.10:8443
export REPRODROID_ENABLE_REAL_BUILDS=true
export REPRODROID_BUILD_SANDBOX=DOCKER
```

実在するLAN addressへ置き換えます。`REPRODROID_ADVERTISED_ENDPOINT`のhostはcertificate SANとAndroid側の保存endpointに使われます。

## Development HTTP例

```bash
export REPRODROID_TRANSPORT_MODE=DEVELOPMENT_HTTP
export REPRODROID_HOST=127.0.0.1
export REPRODROID_PORT=8080
export REPRODROID_ENABLE_API_V2=true
```

development HTTPは認証済み接続として表示されません。LANやpublic internetへbindできません。

## Test専用設定

test fixtureやintegration testのopt-in値は利用者向けproduction設定に含めません。[Development](development.md)に記載されたtest commandの中だけで使用します。
