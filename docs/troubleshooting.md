# Troubleshooting

## Runnerが起動しない

- `REPRODROID_TRANSPORT_MODE`が`DEVELOPMENT_HTTP`または`PAIRED_HTTPS`か確認します。
- ポートが`1..65535`の整数か確認します。
- development HTTPではホストを`127.0.0.1`または`::1`にします。
- paired HTTPSではwildcard bindを使用せず、`REPRODROID_ADVERTISED_ENDPOINT`を設定します。
- CLIとサーバーが同じ`REPRODROID_STATE_DIR`を参照しているか確認します。

## Androidから接続できない

- Androidが入力したoriginとadvertised エンドポイントが完全一致するか確認します。
- LAN/WSL2ではAndroidからホストとポートへ到達できるか確認します。
- ADB reverseを使用する場合は`adb reverse --list`を確認します。
- root pinまたはRunner identityが変わっていないか確認します。
- `principals-list`で接続主体が失効していないか確認します。

pin不一致をHTTP fallbackや証明書検証無効化で回避しません。

## Pairingできない

- paired HTTPS設定で`pairing-open`を実行します。
- [invitationの期限・使用回数](api/pairing.md#requestを作成する)を確認します。
- `pairing-list`の要求 IDとフィンガープリントをAndroid画面と照合します。
- エンドポイント変更後は新しいinvitationを作ります。

## Generic buildが開始しない

- `REPRODROID_ENABLE_REAL_BUILDS=true`を確認します。
- `REPRODROID_BUILD_SANDBOX=DOCKER`を確認します。
- Docker daemonへ現在の利用者が接続できるか確認します。
- リポジトリ URL、full コミット、構成、RCE confirmationを確認します。
- ソース scanがreview待ちの場合はfinding ハッシュを確認して明示的にcontinueします。

## Toolchain不足

- カタログを再取得し、requirementsから計画を再解決します。
- ライセンス、archive ハッシュ、platform、必要容量を確認します。
- 期限切れまたは内容変更済みの計画はexecuteせず、新しい計画を作成します。
- removal後のpartial ディレクトリを手動でinventoryへ追加しません。

## Storage不足

ストレージ summaryとcleanup プレビューを確認します。hold、current comparison、pending 操作、参照中成果物は削除対象になりません。状態ディレクトリ自体を手動で部分削除しません。

## Log exportが失敗する

出力先を存在しない新規ファイルにします。symlink、既存ファイル、書込み不能または安全でない親ディレクトリは拒否されます。
