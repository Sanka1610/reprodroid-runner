# Operations

## 起動と停止

同じRunner identityを維持するため、CLIとserverで同じ`REPRODROID_STATE_DIR`とtransport設定を使用します。

foreground起動:

```bash
reprodroid-runner
```

systemd user service:

```bash
systemctl --user start reprodroid-runner.service
systemctl --user status reprodroid-runner.service
systemctl --user stop reprodroid-runner.service
```

process停止中のJobを成功として記録しません。再起動後はAndroidに表示されるstateを確認し、提供されたretry/reconcile actionだけを使用します。

## 更新

1. 新releaseのZIP、checksum、release manifestを検証します。
2. Runnerを停止します。
3. 現在のstate directoryをそのまま保持します。
4. 新しいdirectoryへZIPを展開します。
5. serviceの`ExecStart`を新しいbinaryへ切り替えます。
6. Runnerを起動し、`GET /v1/health`とAndroidの接続状態を確認します。

SQLite migrationは起動時に適用されます。古いbinaryへ戻す場合、進んだschemaを古いbinaryが読めるとは限らないため、互換表とrelease notesを確認します。

## Stateの保全

`REPRODROID_STATE_DIR`にはSQLite、artifact、log、certificate、credential materialが含まれます。offline backupを作る場合はRunnerを停止し、directory全体を同じpermissionで保全します。個別fileを別のRunner stateへ混ぜません。

backupには秘密情報が含まれます。Git repository、Issue、chat、一般的なcloud共有へ置きません。

## Principalの失効

```bash
reprodroid-runner principals-list
reprodroid-runner principal-revoke <principalId>
```

Android側でcredentialを削除するだけではRunner側の失効を確認できません。RunnerでrevokeしてからAndroidの保存credentialを削除します。

## Ownership adoption

development principal等の既存resourceをpaired principalへ移す場合は、対象件数をpreviewし、同じstateでexecuteします。

```bash
reprodroid-runner adoption-preview <sourcePrincipalId> <targetPrincipalId>
reprodroid-runner adoption-execute <previewId>
```

executeはstale preview、失効principal、resource変化を再検証します。

## Log export

```bash
reprodroid-runner runner-log-export /new/path/runner-log.txt
reprodroid-runner job-log-export <jobId> /new/path/job-log.txt
```

export先は新規fileである必要があります。既存file、symbolic link、安全でない親directoryを拒否し、1回の出力を32 MiB以下に制限します。Job stdout/stderrにはbuild scriptが出力したsecretやpathが含まれる可能性があるため、外部共有前に内容を確認します。

## Storage cleanup

AndroidからRunner storage summaryを取得し、cleanup previewで対象、保護理由、予想解放量を確認してからexecuteします。previewの期限切れ、resource変化、hold、ownership不一致では再previewが必要です。

## 復旧

| 状況 | 対応 |
|---|---|
| certificateまたはpin不一致 | endpointとRunner identityを確認し、意図した変更なら再pair |
| credential失効 | 新しいmanual pairingを実施 |
| SQLite reconciliation required | mutationを停止し、logとstate backupを保全して原因を確認 |
| storage不足 | cleanup previewで保護対象を確認して手動実行 |
| toolchain不完全 | installation detailを確認し、catalogから再計画 |
| Job中断 | 保存stateを確認し、明示されたretry/reconcile actionを使用 |
