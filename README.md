# ReproDroid Runner

ReproDroid AndroidアプリからJobを受け、source取得、検査、許可されたbuild、artifact配信を行うPC側Runnerです。Androidアプリとは独立したリポジトリとリリースサイクルを維持します。

> [!IMPORTANT]
> 現在のRunner versionは`0.1.0-alpha02`、databaseはSQLite12です。Phase 5.6では運用文書だけを同期し、Runner production source、API、schema、dependency、配布内容はPhase 4のローカル受入点から変更していません。このREADMEの状態は、新しいrelease、push、`main`統合、公開を意味しません。

## Repository roles

- `reprodroid-runner`: Runner実装とRunner固有の起動・運用手順
- [`reprodroid`](https://github.com/Sanka1610/reprodroid): Androidアプリと公開横断ドキュメント

公開ADR、API、機能契約は`reprodroid`を正本とします。Runnerの利用に非公開の開発・Evidenceリポジトリは必要ありません。

## Current capabilities

- API v1のJob作成、確認、ログ、cancel／retry、artifact metadata／download
- SQLite12によるJob、監査、artifact、operation、principalの永続化
- allowlistされた固定recipeと、Docker限定generic build
- 解決済みcommitだけを返す限定`git rev-parse` shimを備えた`docker-generic-v3`
- Build Environment Manifestのredacted public projection
- dependency pinning、determinism、pre-build source scan、digest-bound review
- managed toolchain catalog、検証、導入、inventory、manual removal
- storage summary、reservation、hold、二段階manual cleanup
- independent Build A／Bとraw三軸comparison
- loopback development HTTPとpaired HTTPS
- principal失効と明示的なownership adoption
- bounded Runner／Job log export
- public GitHub／Codeberg source registry

API v2で使用する契約識別子は次のとおりです。

```text
foundation@1
storage-retention@1
toolchain-install@1
generic-build@1
apk-comparison@1
runner-authentication@1
codeberg-source@1
```

クライアントは実行中Runnerがadvertiseしたcapabilityを確認し、文書に記載があるだけで利用可能と判断してはいけません。

## Security boundary

Gradle build scriptとpluginは任意コードを実行できます。Wrapperやsourceを検査しても、未信頼sourceの安全性や再現性は保証されません。

- 実buildは既定で無効
- repositoryとrevisionを組にしたallowlist、または制限されたgeneric build contractを要求
- Jobごとに解決済みcommitとRCEリスクの明示確認を要求
- generic buildはDocker必須でHOST fallbackなし
- `docker-generic-v3`のGit互換shimはdetached HEAD確認に必要な3形式だけを受け付け、その他のGit操作を拒否
- Docker socket、DinD、privileged、host network、任意image／mountを許可しない
- Runnerのprivate keyをbuild workspace／container／Androidへ渡さない
- Runnerの`SUCCEEDED`をAPKの再現性、trust、install eligibilityとして扱わない

参照APKの取得、APK identity検査、比較結果のtrust表示、install policyはAndroid側の責務です。

## Requirements

- LinuxまたはWSL2
- JDK 21
- Git
- Docker Engine（generic buildを使う場合のみ）

固定MicroG-RE release profileを使う場合は、別途検査可能なJDK 18を`REPRODROID_JDK_18_HOME`で指定します。managed generic build用のGradle、Android SDK、JDKはRunnerのtoolchain contractに従い導入します。

## Build and run

```bash
./gradlew test
./gradlew build
./gradlew run
```

通常の確認では必要な最小taskだけを実行してください。Docker、device-directory、実source buildを使うintegration testは明示的なopt-inです。

## Configuration

| Environment variable | Default | Purpose |
|---|---|---|
| `REPRODROID_STATE_DIR` | `~/.local/state/reprodroid-runner` | SQLite、ログ、artifact、security state |
| `REPRODROID_HOST` | `127.0.0.1` | bind address |
| `REPRODROID_PORT` | HTTP `8080`／HTTPS `8443` | listen port |
| `REPRODROID_TRANSPORT_MODE` | `DEVELOPMENT_HTTP` | `DEVELOPMENT_HTTP`または`PAIRED_HTTPS` |
| `REPRODROID_ADVERTISED_ENDPOINT` | unset | paired modeで証明書とpairingへ束縛するHTTPS origin |
| `REPRODROID_ENABLE_REAL_BUILDS` | `false` | 実source buildの危険受容 |
| `REPRODROID_ENABLE_API_V2` | `false` | development modeでAPI v2を有効化。paired modeでは常に有効 |
| `REPRODROID_BUILD_SANDBOX` | `HOST` | 対応経路で`HOST`または`DOCKER` |
| `REPRODROID_JDK_18_HOME` | unset | 固定MicroG-RE release profile用JDK 18 |

空値、未知値、安全条件を満たさない組合せはfail closedで拒否します。旧`REPRODROID_ALLOW_UNAUTHENTICATED_NON_LOOPBACK`は受け付けません。

## Connection modes

### Development HTTP

既定の`DEVELOPMENT_HTTP`は正確なloopback bindだけを許可します。

```bash
./gradlew run
adb reverse tcp:8080 tcp:8080
```

ADB reverseはローカル開発用であり、認証済み接続の証拠ではありません。無認証HTTPをLANやpublic internetへ公開しないでください。

### Paired HTTPS

専用state directoryと正確なHTTPS endpointを設定し、未初期化stateに対して一度だけ`security-init`を実行します。

```bash
export REPRODROID_STATE_DIR=/absolute/path/to/runner-state
export REPRODROID_TRANSPORT_MODE=PAIRED_HTTPS
export REPRODROID_HOST=127.0.0.1
export REPRODROID_PORT=8443
export REPRODROID_ADVERTISED_ENDPOINT=https://127.0.0.1:8443
./gradlew installDist
build/install/reprodroid-runner/bin/reprodroid-runner security-init
build/install/reprodroid-runner/bin/reprodroid-runner
```

paired構成に失敗してもHTTPへfallbackしません。LANで使用する場合は、実際のbind先とadvertised endpointを明示し、firewallやrouterを別途管理してください。wildcard bindやpublic internetへの直接露出を前提にしません。

## Local security CLI

同じstate／transport設定を使用するローカルterminalから操作します。

| Command | Purpose |
|---|---|
| `pairing-open` | 5分・1回限りのmanual invitationを作成 |
| `pairing-list` | pending requestとconfirmation fingerprintを表示 |
| `pairing-approve <requestId>` | 画面照合済みの要求を承認 |
| `pairing-reject <requestId>` | 要求を拒否 |
| `principals-list` | principal状態を表示 |
| `principal-revoke <principalId>` | principalを失効 |
| `adoption-preview <sourcePrincipalId> <targetPrincipalId>` | ownership移行をpreview |
| `adoption-execute <previewId>` | 再検証後にownershipをatomic移行 |
| `security-change-endpoint` | 同じrootの新endpoint用leafを発行 |
| `security-rotate-root` | rootを明示交換し、全端末の再pairを要求 |

pairing payload、invitation secret、token、key、credential envelopeをログ、Issue、チャットへ貼らないでください。QR／camera／Google Play servicesは必須経路に含めません。

## Local log export

Runner運用ログとJobログは、Runnerを実行するローカルprincipalが新規出力先へ明示的にexportできます。

```bash
build/install/reprodroid-runner/bin/reprodroid-runner runner-log-export /absolute/path/to/runner-log.txt
build/install/reprodroid-runner/bin/reprodroid-runner job-log-export <jobId> /absolute/path/to/job-log.txt
```

既存ファイル、symbolic link、安全でない親directoryを拒否し、1回の出力を32 MiB以下に制限します。Runner自身の運用ログでは秘密値を記録しない設計ですが、Job stdout／stderrにはbuild scriptが出力した秘密やpathが含まれる可能性があります。内容を確認せず外部送信しないでください。自動送信や診断共有APIはありません。

## Public documentation

- [Documentation index](https://github.com/Sanka1610/reprodroid/tree/main/docs)
- [Architecture overview](https://github.com/Sanka1610/reprodroid/blob/main/docs/architecture/overview.md)
- [UI architecture and navigation](https://github.com/Sanka1610/reprodroid/blob/main/docs/architecture/ui.md)
- [Getting started](https://github.com/Sanka1610/reprodroid/blob/main/docs/guides/getting-started.md)
- [Operations and recovery](https://github.com/Sanka1610/reprodroid/blob/main/docs/guides/operations.md)
- [Unreleased notes](https://github.com/Sanka1610/reprodroid/blob/main/docs/releases/unreleased.md)
- [Runner API v1](https://github.com/Sanka1610/reprodroid/blob/main/docs/api/runner-api.md)
- [Runner API v2](https://github.com/Sanka1610/reprodroid/blob/main/docs/api/runner-api-v2.md)
- [ADR index](https://github.com/Sanka1610/reprodroid/blob/main/docs/adr/README.md)
- [Feature contracts](https://github.com/Sanka1610/reprodroid/blob/main/docs/design/README.md)
- [Current status](https://github.com/Sanka1610/reprodroid/blob/main/docs/status/current.md)

## Scope exclusions

- 未登録sourceを安全に実行できるという保証
- Play Store／F-Droid配布
- Google Play services依存
- split APK、APKS、AAB
- QR／cameraを必須にするpairing
- 自動Issue作成、診断共有、ログ自動送信
- CI／Runnerへのproduction Android signing key配布

## License

ReproDroid Runner自身のcode、設定、文書、resourceは[Apache License 2.0](LICENSE)です。配布物には[third-party notices](THIRD_PARTY_LICENSES.md)を同梱します。各releaseではresolved dependency graphとnative bundle noticeを再確認してください。
