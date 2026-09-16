# Installation

## 必要要件

- LinuxまたはWSL2
- JDK 21
- Git
- generic buildを使う場合はDocker Engine
- paired接続では、Android端末から到達できるHTTPS origin

## Release assetを検証する

[v0.1.0-alpha02 release](https://github.com/Sanka1610/reprodroid-runner/releases/tag/v0.1.0-alpha02)からZIP、CycloneDX JSON SBOM、`SHA256SUMS`、`release-manifest.json`を同じdirectoryへ取得します。

```bash
sha256sum -c SHA256SUMS
sha256sum reprodroid-runner-0.1.0-alpha02.zip
```

計算結果をmanifestの`assets[].sha256`と照合します。asset名、byte数、digest、tag、source commitのいずれかが一致しない場合は展開しません。

## 展開する

配布物と永続stateを分離します。

```bash
mkdir -p "$HOME/.local/opt"
mkdir -p "$HOME/.local/state/reprodroid-runner"
unzip reprodroid-runner-0.1.0-alpha02.zip -d "$HOME/.local/opt"
```

配布物の実行fileは次です。

```text
$HOME/.local/opt/reprodroid-runner-0.1.0-alpha02/bin/reprodroid-runner
```

ZIPを更新しても、`REPRODROID_STATE_DIR`で指定するSQLite、certificate、credential、log、artifact directoryは削除・上書きしません。

## Paired HTTPSを初期化する

loopbackとADB reverseだけで使用する例:

```bash
export REPRODROID_STATE_DIR="$HOME/.local/state/reprodroid-runner"
export REPRODROID_TRANSPORT_MODE=PAIRED_HTTPS
export REPRODROID_HOST=127.0.0.1
export REPRODROID_PORT=8443
export REPRODROID_ADVERTISED_ENDPOINT=https://127.0.0.1:8443
RUNNER="$HOME/.local/opt/reprodroid-runner-0.1.0-alpha02/bin/reprodroid-runner"
"$RUNNER" security-init
"$RUNNER"
```

`security-init`は未初期化stateにだけ実行します。既存rootを交換する操作は`security-rotate-root`であり、登録済みAndroid端末の再pairが必要です。

LAN接続ではAndroid端末から到達できるhost/IPを`REPRODROID_HOST`と`REPRODROID_ADVERTISED_ENDPOINT`へ設定します。wildcard bindはpaired modeでも拒否されます。

## systemd user service

[example service](../examples/reprodroid-runner.service)と[example environment](../examples/reprodroid-runner.env.example)をrepositoryから取得し、利用者ごとの設定directoryへ配置します。binary pathとendpointを実際の値へ変更します。`REPRODROID_STATE_DIR`を省略した場合は、そのuserの`~/.local/state/reprodroid-runner`を使用します。現在公開されているalpha02 ZIPには、この文書追加後のexample fileは含まれません。

```bash
systemctl --user daemon-reload
systemctl --user enable --now reprodroid-runner.service
```

service開始前にterminalから`security-init`を完了させます。environment fileにinvitation secret、token、private key、Android credentialを記録しません。
