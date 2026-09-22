# Configuration

Runnerは起動時に環境変数を読み取ります。定義は[Application.kt](../src/main/kotlin/com/sanka1610/reprodroid/runner/Application.kt)の`RunnerConfig.fromEnvironment`と`parsePairedEndpoint`です。真偽値の設定は大文字・小文字を区別せず`true`だけを有効とし、その他の値では無効になります。接続方式、ポート、保存先などの不正値は起動時に拒否します。

| Variable | Default | 現在の動作 |
|---|---|---|
| `REPRODROID_STATE_DIR` | `~/.local/state/reprodroid-runner` | SQLite、認証状態、ログ、成果物を保存。空値を拒否 |
| `REPRODROID_TRANSPORT_MODE` | `DEVELOPMENT_HTTP` | `DEVELOPMENT_HTTP`または`PAIRED_HTTPS`のみ |
| `REPRODROID_HOST` | `127.0.0.1` | development HTTPは`127.0.0.1`または`::1`のみ。paired HTTPSはwildcardを拒否 |
| `REPRODROID_PORT` | HTTP `8080`、HTTPS `8443` | `1..65535`の整数 |
| `REPRODROID_ADVERTISED_ENDPOINT` | unset | paired HTTPSで必須。設定ポートを含むHTTPS origin。末尾`/`は除去し、ユーザー情報・クエリ・フラグメント・他のパスは拒否 |
| `REPRODROID_ENABLE_REAL_BUILDS` | `false` | `true`の場合だけソースからの実ビルドを許可 |
| `REPRODROID_ENABLE_API_V2` | `false` | loopback developmentでv2を有効化。paired HTTPSでは常に有効 |
| `REPRODROID_BUILD_SANDBOX` | `HOST` | `HOST`または`DOCKER`。汎用ビルドは`DOCKER`を要求 |
| `REPRODROID_JDK_18_HOME` | unset | 固定MicroG-REレシピ用。`BuildRecipeRegistry`がJDK 18を検査 |

旧`REPRODROID_ALLOW_UNAUTHENTICATED_NON_LOOPBACK=true`は起動時に拒否します。

`REPRODROID_JDK_18_HOME`の検査は[BuildRecipes.kt](../src/main/kotlin/com/sanka1610/reprodroid/runner/BuildRecipes.kt)の`BuildRecipeRegistry`にあります。

初回起動の設定例は[導入](installation.md#paired-httpsを初期化する)、LAN／開発用HTTPは[接続](networking-and-pairing.md)、実ビルドの有効化は[ビルド手順](builds-and-toolchains.md#buildの開始条件)を参照してください。
