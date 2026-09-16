# Troubleshooting

## Runnerが起動しない

- `REPRODROID_TRANSPORT_MODE`が`DEVELOPMENT_HTTP`または`PAIRED_HTTPS`か確認します。
- portが`1..65535`の整数か確認します。
- development HTTPではhostを`127.0.0.1`または`::1`にします。
- paired HTTPSではwildcard bindを使用せず、`REPRODROID_ADVERTISED_ENDPOINT`を設定します。
- CLIとserverが同じ`REPRODROID_STATE_DIR`を参照しているか確認します。

## Androidから接続できない

- Androidが入力したoriginとadvertised endpointが完全一致するか確認します。
- LAN/WSL2ではAndroidからhostとportへ到達できるか確認します。
- ADB reverseを使用する場合は`adb reverse --list`を確認します。
- root pinまたはRunner identityが変わっていないか確認します。
- `principals-list`でprincipalが失効していないか確認します。

pin不一致をHTTP fallbackやcertificate検証無効化で回避しません。

## Pairingできない

- paired HTTPS設定で`pairing-open`を実行します。
- invitationは5分以内に1回だけ使用します。
- `pairing-list`のrequest IDとfingerprintをAndroid画面と照合します。
- endpoint変更後は新しいinvitationを作ります。

## Generic buildが開始しない

- `REPRODROID_ENABLE_REAL_BUILDS=true`を確認します。
- `REPRODROID_BUILD_SANDBOX=DOCKER`を確認します。
- Docker daemonへ現在のuserが接続できるか確認します。
- repository URL、full commit、configuration、RCE confirmationを確認します。
- source scanがreview待ちの場合はfinding digestを確認して明示的にcontinueします。

## Toolchain不足

- catalogを再取得し、requirementsからplanを再解決します。
- license、archive digest、platform、必要容量を確認します。
- stale planはexecuteせず、新しいplanを作成します。
- removal後のpartial directoryを手動でinventoryへ追加しません。

## Storage不足

storage summaryとcleanup previewを確認します。hold、current comparison、pending operation、参照中artifactは削除対象になりません。state directory自体を手動で部分削除しません。

## Log exportが失敗する

export先を存在しない新規fileにします。symlink、既存file、書込み不能または安全でない親directoryは拒否されます。
