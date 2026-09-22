# Runnerのセキュリティ

## 脅威境界

Runnerは第三者のGradleプロジェクトをビルドします。スクリプト、プラグイン、コンパイラ、ネイティブツールは任意コードを実行するため、実行対象のコミットとスキャン結果を確認して承認してください。

## Transportと認証

開発用HTTPはループバックに限定され、固定のローカル接続主体を使います。paired HTTPSでは手動ペアリングで登録した端末別の認証情報とルート公開鍵の固定を使います。通常APIの認証は[PairingApi.kt](../src/main/kotlin/com/sanka1610/reprodroid/runner/PairingApi.kt)の`runnerAuthentication`と`requirePrincipal`、証明書・認証情報・失効は[RunnerSecurity.kt](../src/main/kotlin/com/sanka1610/reprodroid/runner/RunnerSecurity.kt)の`LocalCertificateAuthority`と`RunnerSecurityStore`が管理します。

接続の操作は[接続ガイド](networking-and-pairing.md)、要求の形式は[共通API](api/common.md#originと認証)を参照してください。

## Build isolation

汎用ビルドはDockerを使用します。Runnerがイメージとツールチェーンのマウントを選択し、Docker socket、特権モード、host network、要求側からの任意マウントを許可しません。Runnerの秘密鍵、Androidの認証情報、製品APKの署名鍵はコンテナへ渡しません。

プロファイルとリソース上限は[BuildSandbox.kt](../src/main/kotlin/com/sanka1610/reprodroid/runner/BuildSandbox.kt)の`GenericDockerSandboxProfile`と`DetachedGitGenericDockerSandboxProfile`で定義します。現行プロファイルには固定の送信先許可リストとJob単位のディスク容量・inode数の強制上限がありません。ビルド実行前にホストの空き容量とネットワーク到達範囲を確認してください。

## ソースと成果物

ソースURLは[BuildRecipes.kt](../src/main/kotlin/com/sanka1610/reprodroid/runner/BuildRecipes.kt)の`BuildRecipeRegistry.canonicalRepositoryKey`で検査します。実行前に参照を完全なコミットSHAへ解決します。

成果物は専用ディレクトリへ保存し、配信直前に登録済みサイズ・SHA-256と照合します。パストラバーサル、シンボリックリンク、登録外の成果物を拒否します。応答と失敗条件は[Artifact API](api/jobs-v1.md#artifact)を参照してください。

## 秘密情報

Runnerの秘密鍵、invitation、端末の認証情報、Android署名用keystore・passphraseは、リポジトリ・ビルド保存先・コンテナ・Issue・一般ログへ保存しません。Runnerのデータ保全コピーとJobログには秘密情報が含まれ得るため、共有前に内容と保存先を確認してください。

## Data retention

保存先の保全・削除・ログ出力は[運用](operations.md)、削除時の保護条件は[Storage API](api/storage-v2.md#cleanup)を参照してください。
