# ReproDroid Runner documentation

Runner `0.1.0-alpha02`の導入、設定、運用、APIを説明します。

## 利用者向け

- [Installation](installation.md): Release ZIPの検証、展開、初期起動
- [Networking and pairing](networking-and-pairing.md): ADB reverse、LAN、WSL2、HTTPS、manual pairing
- [Configuration](configuration.md): production設定とfail-closed条件
- [Builds and toolchains](builds-and-toolchains.md): source scan、Docker、managed toolchain、Build A／B
- [Operations](operations.md): 起動停止、更新、state保全、log export、失効、復旧
- [Security](security.md): 認証、任意コード実行、container、秘密情報の境界
- [Troubleshooting](troubleshooting.md): 接続、certificate、build、toolchain、storage

## 開発者向け

- [Development](development.md): source checkout、test、distribution作成
- [API](api/README.md): v1、v2、pairingのwire contract

Androidアプリ側の操作、比較判定、trust、signer、installは[ReproDroid documentation](https://github.com/Sanka1610/reprodroid/tree/main/docs)を参照してください。
