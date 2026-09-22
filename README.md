# ReproDroid Runner

ReproDroid Runnerは、Androidアプリから依頼されたソース取得、検査、ビルド、成果物の配信をPC上で実行します。ソースをコミットへ固定し、利用者の確認を受けてBuild A／Bを独立して作成します。

## クイックスタート

1. [必要環境とインストール手順](docs/installation.md)を確認し、[GitHub Releases](https://github.com/Sanka1610/reprodroid-runner/releases)から対象版のZIPを取得・展開します。
2. 専用の状態保存先とHTTPS接続を設定し、`security-init`を実行してRunnerを起動します。
3. [手動ペアリング](docs/networking-and-pairing.md#manual-pairing)でPCとAndroidのフィンガープリントを照合します。
4. [ビルドとツールチェーン](docs/builds-and-toolchains.md)を設定し、Androidから実行します。

対応するAndroid版は対象Releaseの`release-manifest.json`にある`compatibleAndroid`で確認します。稼働中の版と機能は[healthとcapability API](docs/api/common.md#health)で取得できます。

## 目的別のガイド

| 目的 | 参照先 |
|---|---|
| 初期設定・起動 | [導入](docs/installation.md) |
| ADB reverse、LAN、WSL2、ペアリング | [接続](docs/networking-and-pairing.md) |
| 環境変数の既定値と制限 | [設定](docs/configuration.md) |
| 更新、CLI、ログ、データ保全、復旧 | [運用](docs/operations.md) |
| ビルド環境と秘密情報の管理 | [セキュリティ](docs/security.md) |
| エラーへの対処 | [トラブルシューティング](docs/troubleshooting.md) |
| ソースからのビルドとAPI連携 | [開発](docs/development.md)・[API](docs/api/README.md) |

## ライセンス

ReproDroid Runnerは[Apache License 2.0](LICENSE)です。配布物には[第三者ソフトウェアのライセンス情報](THIRD_PARTY_LICENSES.md)を同梱します。
