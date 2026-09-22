# Runnerの導入

## 必要要件

- LinuxまたはWSL2
- JDK 21とGit
- 汎用ビルドを使う場合はDocker Engine
- ペアリングするAndroid端末から到達できるHTTPS接続先

JDKの指定は[build.gradle.kts](../build.gradle.kts)の`jvmToolchain`、配布構成は`application`と`distributions`を参照してください。固定レシピ固有の要件は[ビルド手順](builds-and-toolchains.md)に記載しています。

## Releaseを取得して検証する

[GitHub Releases](https://github.com/Sanka1610/reprodroid-runner/releases)から、対象版のZIP、CycloneDX JSON SBOM、`SHA256SUMS`、`release-manifest.json`を同じディレクトリへ取得します。

```bash
sha256sum -c SHA256SUMS
```

`release-manifest.json`の`release`、`tag`、`sourceCommit`、`assets`、`compatibleAndroid`を確認します。ファイル名・サイズ・SHA-256が一致してから展開してください。

## 展開する

`RUNNER_VERSION`を取得したReleaseの版へ変更して実行します。

```bash
RUNNER_VERSION=0.1.0-alpha02 # 使用するReleaseの版
mkdir -p "$HOME/.local/opt" "$HOME/.local/state/reprodroid-runner"
unzip "reprodroid-runner-${RUNNER_VERSION}.zip" -d "$HOME/.local/opt"
RUNNER="$HOME/.local/opt/reprodroid-runner-${RUNNER_VERSION}/bin/reprodroid-runner"
```

配布物と永続データの保存先を分けます。更新時も`REPRODROID_STATE_DIR`のデータを保持してください。

## Paired HTTPSを初期化する

次はADB reverseで接続する例です。同じターミナルで続けて実行します。

```bash
export REPRODROID_STATE_DIR="$HOME/.local/state/reprodroid-runner"
export REPRODROID_TRANSPORT_MODE=PAIRED_HTTPS
export REPRODROID_HOST=127.0.0.1
export REPRODROID_PORT=8443
export REPRODROID_ADVERTISED_ENDPOINT=https://127.0.0.1:8443
"$RUNNER" security-init
"$RUNNER"
```

`security-init`は未初期化の保存先に実行します。既存証明書を使う場合は起動だけを行ってください。起動後は[ペアリング](networking-and-pairing.md#manual-pairing)へ進みます。LAN／WSL2からの接続は[接続ガイド](networking-and-pairing.md#linux-lan)で設定します。

## systemd user service

[service例](../examples/reprodroid-runner.service)と[環境変数の例](../examples/reprodroid-runner.env.example)をチェックアウトから配置します。

```bash
mkdir -p "$HOME/.config/systemd/user" "$HOME/.config/reprodroid-runner"
cp examples/reprodroid-runner.service "$HOME/.config/systemd/user/"
cp examples/reprodroid-runner.env.example "$HOME/.config/reprodroid-runner/runner.env"
```

コピーしたserviceの`ExecStart`を実際に展開した実行ファイルへ変更します。`runner.env`の接続先も変更し、同じ設定で`security-init`を完了してから起動します。

```bash
systemctl --user daemon-reload
systemctl --user enable --now reprodroid-runner.service
```

環境変数の意味は[設定](configuration.md)、更新・停止は[運用](operations.md)を参照してください。配布ZIPの同梱内容は`unzip -l <取得したZIP>`で確認できます。例ファイルがない場合は、使用する文書のリビジョンから取得してください。
