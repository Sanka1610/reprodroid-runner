# Security

## 脅威境界

Runnerは第三者のGradle projectを扱うため、build script、plugin、compiler、native toolは任意コードを実行できます。source scan、dependency pinning、container、Build A／Bは観察可能性とcontainmentを改善しますが、sourceの安全性を保証しません。

## Transportと認証

- development HTTPはexact loopbackだけにbindし、認証済み端末として扱いません。
- paired HTTPSはmanual invitation、PC承認、root public-key pin、端末別credentialを使用します。
- 通常のv1/v2 routeはpaired principalを要求します。
- pairing routeは期限付きinvitationの範囲だけを扱い、通常APIの認証を迂回しません。
- credential失効後のrequestは拒否します。

## Build isolation

generic buildはDockerを使用します。containerへDocker socket、privileged、host network、任意mount、Runner private key、Android credential、production Android signing keyを渡しません。

現在のprofileに含まれない境界:

- 固定egress allowlist
- Job単位のhard disk quota
- Job単位のinode quota
- 第三者によるremote attestation
- malicious kernel/container escapeへの保証

## Sourceとartifact

- repository URLをpublic GitHub/Codebergの閉集合へ正規化します。
- build前にrefをfull commit SHAへ解決し、利用者確認へ表示します。
- artifactは専用directoryへ移し、download直前にsizeとSHA-256を再検査します。
- path traversal、symbolic link、登録外artifactを拒否します。
- `SUCCEEDED`はbuild完了であり、official APKとの一致やinstall可能性ではありません。

## 秘密情報

次をrepository、build workspace、container、Issue、chat、一般logへ保存しません。

- Runner root/leaf private key
- invitation secret
- bearer credential
- Android client private material
- production Android signing keystore/passphrase
- unreviewed state backup

Runnerのlocal state backupとJob logは機微情報として扱います。

## Data retention

Job、operation、artifact、log、principalはSQLite12とstate directoryへ保存されます。cleanupはpreview-firstで、current/pending/referenced resourceとhold対象を保護します。log exportはdiagnostic recordであり、database backupではありません。
