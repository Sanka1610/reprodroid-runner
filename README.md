# ReproDroid Runner

ReproDroid Runnerは、ReproDroid Androidアプリから依頼されたsource取得、検査、build、artifact配信をPC上で実行します。Androidアプリとは独立したrelease cycleを持ち、現在のreleaseは`0.1.0-alpha02`です。

## 主な機能

- public GitHub／Codeberg repositoryからのsource取得
- full commit SHAへ固定したJobと、実行前のRCE確認
- build前source scanと明示的なreview gate
- allowlist済みrecipeとDocker限定generic build
- managed toolchainの検証、導入、inventory、manual removal
- 独立したBuild A／Bとraw APK comparison
- SQLite12によるJob、operation、artifact、principalの永続化
- manual pairing、root pin付きHTTPS、端末別credentialと失効
- preview-first cleanupとbounded log export

## 対応関係

| Runner | Android | Java | Database | API |
|---|---|---|---|---|
| `0.1.0-alpha02` | `0.1.0-alpha05` | JDK 21 | SQLite12 | v1、v2、pairing v1 |

API v2で使用するcapabilityは`foundation@1`、`storage-retention@1`、`toolchain-install@1`、`generic-build@1`、`apk-comparison@1`、`runner-authentication@1`、`codeberg-source@1`です。実行中Runnerの`GET /v2/capabilities`応答を確認してから機能を使用します。

## 必要要件

- LinuxまたはWSL2
- JDK 21
- Git
- Docker Engine（generic buildを使用する場合）
- Android端末から到達できるHTTPS endpoint（paired接続の場合）

固定MicroG-RE recipeでは、別途JDK 18を`REPRODROID_JDK_18_HOME`で指定します。

## Release ZIPからインストール

1. [v0.1.0-alpha02 release](https://github.com/Sanka1610/reprodroid-runner/releases/tag/v0.1.0-alpha02)から次を取得します。
   - `reprodroid-runner-0.1.0-alpha02.zip`
   - `reprodroid-runner-0.1.0-alpha02.cyclonedx.json`
   - `SHA256SUMS`
   - `release-manifest.json`
2. checksumを確認します。

   ```bash
   sha256sum -c SHA256SUMS
   ```

3. ZIPを、Runnerのstate directoryとは別の場所へ展開します。

   ```bash
   mkdir -p "$HOME/.local/opt"
   unzip reprodroid-runner-0.1.0-alpha02.zip -d "$HOME/.local/opt"
   ```

4. 専用state directoryとpaired HTTPS設定を用意します。

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

`REPRODROID_ADVERTISED_ENDPOINT`にはAndroid端末が実際に接続するoriginを指定します。LAN接続ではloopbackを使用せず、証明書SAN、bind address、firewallを同じ到達経路に合わせます。詳しくは[Installation](docs/installation.md)と[Networking and pairing](docs/networking-and-pairing.md)を参照してください。

## Androidアプリとペアリング

Runnerを同じ設定で起動した状態で、別terminalから次を実行します。

```bash
"$RUNNER" pairing-open
```

表示されたinvitationをAndroidのSettings → Runner → Runner settings and authenticationへ入力します。AndroidとPCのconfirmation fingerprintを照合し、一致したrequestだけを承認します。

```bash
"$RUNNER" pairing-list
"$RUNNER" pairing-approve <requestId>
```

invitation secret、token、credential、private keyをIssue、chat、screenshot、logへ貼り付けないでください。

## 主なコマンド

| Command | 用途 |
|---|---|
| `security-init [host]` | 未初期化stateへroot/leaf certificateを作成 |
| `security-change-endpoint` | 同じrootで新endpoint用leafを作成 |
| `security-rotate-root [host]` | rootを交換し、全clientの再pairを要求 |
| `pairing-open` | 5分・1回限りのmanual invitationを作成 |
| `pairing-list` | pending requestを表示 |
| `pairing-approve <requestId>` | fingerprint照合済みrequestを承認 |
| `pairing-reject <requestId>` | requestを拒否 |
| `principals-list` | principal一覧を表示 |
| `principal-revoke <principalId>` | principalを失効 |
| `adoption-preview <sourcePrincipalId> <targetPrincipalId>` | ownership移行をpreview |
| `adoption-execute <previewId>` | ownership移行を再検証して実行 |
| `runner-log-export <output>` | Runner運用logを新規fileへexport |
| `job-log-export <jobId> <output>` | 指定Job logを新規fileへexport |

全設定値は[Configuration](docs/configuration.md)、起動停止・更新・backup・復旧は[Operations](docs/operations.md)、APIは[API index](docs/api/README.md)を参照してください。

## セキュリティと既知の制限

- Gradle build scriptとpluginは任意コードを実行できます。実buildは既定で無効です。
- generic buildはDockerを要求し、HOST fallbackを使用しません。
- Docker profileはDocker socket、privileged、host network、任意image／mountを渡しません。
- 現在のDocker bridgeには固定egress allowlistとJob単位のhard disk／inode quotaがありません。
- Runnerの`SUCCEEDED`はbuild完了を表し、APKの再現性、trust、signer互換性、install eligibilityを確定しません。
- production signing key、Android credential、Runner private keyをbuild workspaceやcontainerへ渡しません。

詳細は[Security](docs/security.md)を参照してください。

## 開発

source checkoutからのbuildとtestは[Development](docs/development.md)を参照してください。通常利用ではRelease ZIPを使用します。

## ドキュメント

- [Documentation index](docs/README.md)
- [Installation](docs/installation.md)
- [Networking and pairing](docs/networking-and-pairing.md)
- [Configuration](docs/configuration.md)
- [Builds and toolchains](docs/builds-and-toolchains.md)
- [Operations](docs/operations.md)
- [Security](docs/security.md)
- [Troubleshooting](docs/troubleshooting.md)
- [API](docs/api/README.md)
- [Changelog](CHANGELOG.md)

## ライセンス

ReproDroid Runnerは[Apache License 2.0](LICENSE)です。配布物には[third-party notices](THIRD_PARTY_LICENSES.md)を同梱します。
