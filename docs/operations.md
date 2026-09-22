# Runnerの運用

## CLI一覧

引数の定義は[Application.kt](../src/main/kotlin/com/sanka1610/reprodroid/runner/Application.kt)の`validateLocalCommand`と`runLocalCommand`です。以下の例で`reprodroid-runner`は展開先の実行ファイルを指します。PATHへ追加するか、絶対パスで実行してください。

| コマンド | 用途 |
|---|---|
| `security-init [host]` | 未初期化の保存先へ証明書を作成 |
| `security-change-endpoint` | 同じルート証明書で接続先を変更 |
| `security-rotate-root [host]` | ルート証明書を交換し、全端末を再ペアリング |
| `pairing-open` | 手動ペアリングのinvitationを発行 |
| `pairing-list` | 確認待ちの要求を表示 |
| `pairing-approve <requestId>` | 照合済みの要求を承認 |
| `pairing-reject <requestId>` | 要求を拒否 |
| `principals-list` | 接続主体の一覧を表示 |
| `principal-revoke <principalId>` | 接続主体を失効 |
| `adoption-preview <sourcePrincipalId> <targetPrincipalId>` | 所有権移行の対象を確認 |
| `adoption-execute <previewId>` | 所有権移行を実行 |
| `runner-log-export <output>` | Runnerのログを新規ファイルへ出力 |
| `job-log-export <jobId> <output>` | 指定Jobのログを新規ファイルへ出力 |

## 起動と停止

同じRunner identityを維持するため、CLIとサーバーで同じ`REPRODROID_STATE_DIR`とtransport設定を使用します。

フォアグラウンド起動:

```bash
reprodroid-runner
```

systemdユーザーサービス:

```bash
systemctl --user start reprodroid-runner.service
systemctl --user status reprodroid-runner.service
systemctl --user stop reprodroid-runner.service
```

中断したJobは、再起動時に保存済みの状態から復旧処理を行います。再起動後はAndroidに表示される状態を確認し、提供されたretry/reconcile actionだけを使用します。

## 更新

1. 新リリースのZIP、チェックサム、リリース manifestを検証します。
2. Runnerを停止します。
3. 現在の状態保存ディレクトリをそのまま保持します。
4. 新しいディレクトリへZIPを展開します。
5. serviceの`ExecStart`を新しいbinaryへ切り替えます。
6. Runnerを起動し、`GET /v1/health`とAndroidの接続状態を確認します。

SQLite 移行は起動時に適用されます。古いbinaryへ戻す場合、進んだスキーマを古いbinaryが読めるとは限らないため、互換表とリリース notesを確認します。

## Stateの保全

`REPRODROID_STATE_DIR`にはSQLite、成果物、ログ、証明書、認証情報 materialが含まれます。offline バックアップを作る場合はRunnerを停止し、ディレクトリ全体を同じ権限で保全します。個別ファイルを別のRunner 状態へ混ぜません。

バックアップには秘密情報が含まれます。Git リポジトリ、Issue、chat、一般的なcloud共有へ置きません。

## Principalの失効

```bash
reprodroid-runner principals-list
reprodroid-runner principal-revoke <principalId>
```

Android側で認証情報を削除するだけではRunner側の失効を確認できません。RunnerでrevokeしてからAndroidの保存認証情報を削除します。

## Ownership adoption

development 接続主体等の既存リソースをpaired 接続主体へ移す場合は、対象件数をプレビューし、同じ状態でexecuteします。

```bash
reprodroid-runner adoption-preview <sourcePrincipalId> <targetPrincipalId>
reprodroid-runner adoption-execute <previewId>
```

executeは期限切れまたは内容変更済みのプレビュー、失効接続主体、リソース変化を再検証します。

## Log export

出力先の親ディレクトリを用意し、`EXPORT_DIR`に指定します。

```bash
EXPORT_DIR="$HOME/reprodroid-exports"
mkdir -p "$EXPORT_DIR"
reprodroid-runner runner-log-export "$EXPORT_DIR/runner-log.txt"
reprodroid-runner job-log-export <jobId> "$EXPORT_DIR/job-log.txt"
```

制限は[LogExport.kt](../src/main/kotlin/com/sanka1610/reprodroid/runner/LogExport.kt)の`RunnerLogExporter`を参照してください。出力先は新規ファイルである必要があります。既存ファイル、symbolic link、安全でない親ディレクトリを拒否し、1回の出力を32 MiB以下に制限します。Job stdout/stderrにはビルド scriptが出力した秘密情報やパスが含まれる可能性があるため、外部共有前に内容を確認します。

## Storage cleanup

AndroidからRunner ストレージ summaryを取得し、cleanup プレビューで対象、保護理由、予想解放量を確認してからexecuteします。プレビューの期限切れ、リソース変化、hold、所有権不一致では再プレビューが必要です。

## 復旧

| 状況 | 対応 |
|---|---|
| 証明書またはpin不一致 | エンドポイントとRunner identityを確認し、意図した変更なら再pair |
| 認証情報失効 | 新しい手動ペアリングを実施 |
| SQLite reconciliation required | 更新操作を停止し、ログと状態バックアップを保全して原因を確認 |
| ストレージ不足 | cleanup プレビューで保護対象を確認して手動実行 |
| ツールチェーン不完全 | installation detailを確認し、カタログから再計画 |
| Job中断 | 保存状態を確認し、明示されたretry/reconcile actionを使用 |
