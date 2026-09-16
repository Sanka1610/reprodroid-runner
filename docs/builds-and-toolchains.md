# Builds and toolchains

## Buildの開始条件

実source buildは`REPRODROID_ENABLE_REAL_BUILDS=true`の場合だけ開始できます。各Jobはrepository、解決済みfull commit SHA、recipe/configuration、sandbox、toolchainを固定し、RCEリスクの明示確認を要求します。

generic buildはさらに次を要求します。

```bash
export REPRODROID_ENABLE_REAL_BUILDS=true
export REPRODROID_BUILD_SANDBOX=DOCKER
```

generic requestから任意shell、JVM option、environment、container image、mountを指定することはできません。

## Source取得とscan

Runnerはpublic `github.com`と`codeberg.org`のHTTPS repository URLを受け付けます。credential、明示port、query、fragment、余分なpath、encoded traversalを拒否し、resolved full commitに固定してから実行します。

source scanはWrapper、build script、plugin、native execution、network/process等のconfigured indicatorを検出します。findingがある場合は、保存されたscan digestに対する明示的なcontinueが必要です。findingがないことはsourceの安全性を証明しません。

## Docker境界

generic build containerは次を固定します。

- Runnerが選択したimageとtoolchain mount
- detached checkoutと限定Git互換shim
- Docker socketなし
- privileged modeなし
- host networkなし
- 任意image／mountなし
- Runner security state、private key、Android credentialなし

現在のbridge networkには固定egress allowlistがなく、Job単位のhard disk／inode quotaもありません。source repositoryとdependency serverへの通信内容は第三者attestationになりません。

## Managed toolchain

Androidは次の順でtoolchainを管理します。

1. `GET /v2/toolchains/catalog`
2. `POST /v2/toolchains/plans:resolve`
3. licenseとartifact digest／sizeを確認
4. `POST /v2/toolchains/installations`
5. operationとinstallation detailをpoll
6. `GET /v2/toolchains/inventory`

archive digest、展開size、entry数、path、publisher policy、content manifestを検査してからatomicに公開します。removalはpreviewとexecuteを分離し、使用中または保護対象のtoolchainを削除しません。

## Build A／B

Build AとBuild Bは、同じrelease observationとconfiguration hashを使いますが、別Job、別checkout、別build home、別artifactとして実行します。片方のcommit確認、RCE確認、scan確認を他方へ継承しません。

Runnerが作成するraw comparisonは次の三軸を区別します。

- Official vs Build A
- Official vs Build B
- Build A vs Build B

`MATCH`、`DIFFERENT`、`INCOMPARABLE`を保存し、resource不足による限定retryでraw結果を成功へ書き換えません。Android側の`Reproducible`表示条件は[Reproducibility](https://github.com/Sanka1610/reprodroid/blob/main/docs/reproducibility.md)を参照してください。
