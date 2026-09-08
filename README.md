# ReproDroid Runner

**Phase 4.7実装・製品受入完了（2026-09-09）:** public `github.com`／`codeberg.org`だけの閉じたsource registryと`codeberg-source@1`をSQLite12／API v2へ追加しました。JDK導入時は`bin/java`と`release`に加えて非symlink regular fileの`lib/jspawnhelper`を必須化し、`bin/*`とhelperだけの実行bitを復元・content manifestへ束縛します。API 37の正規SDK package directoryはcatalog、inventory、host／Docker preflightで`android-37.0`へ統一し、旧`android-37`や自動SDK導入へfallbackしません。新規Jobは`docker-generic-v2`の分離tmpfs（`/tmp` 960 MiB `noexec`、`/run/reprodroid-native` 64 MiB `exec`）を使用し、SQLiteの並行ログ追記はWALを導入せず`IMMEDIATE` transactionで直列化します。Runner全JVMは217件、failure／error 0、明示opt-in skip 16件です。製品UIからの負経路では固定Wrapper不一致、source scan上限、旧SDK配置不一致をfail closedに拒否し、artifactを受入しないことを確認しました。修正後の`qwerty287/ftpclient`独立Build A／Bは成功し、Androidがraw三軸をOfficial vs A `DIFFERENT`、Official vs B `DIFFERENT`、A vs B `MATCH`として保存・表示しました。[Phase 4.7実装記録](../reprodroid-project/reports/2026/09/2026-09-08-phase-4-7-implementation.md)を参照してください。

**Phase 4.6実装・受入完了（2026-09-08）:** exclusive development HTTP／paired HTTPS、Runner-local P-256 CAとrenewable leaf、root SPKI pin、local CLI承認／失効、Android生成Bearerのhash保存、全通常API認証、明示LAN、legacy principalのpreview付きatomic adoption、verified SQLite12 migrationを実装しました。Runner全197件中、既存Docker環境gate 16件をskipし181件がPASS、failure／error 0です。実TLS／CLI 26観測、ADB reverseのAndroid pairing／失効、同一root leaf更新、root交換後の旧client拒否・再pair、task-key署名release、ADB reverseなしのAndroid 16 emulator private-host `10.0.2.2:8443`におけるallow／deny／revoke／re-grantもPASSです。RC46は44 PASS／0 PARTIAL／0 NOT_RUNで、firewall／routerは変更していません。QR／camera／Play servicesは追加していません。[実装記録](../reprodroid-project/reports/2026/09/2026-09-08-phase-4-6-implementation.md)を参照してください。

**Phase 4.5境界（2026-09-06）:** scheduled release discovery／notificationをAndroid単体のmetadata-only機能として実装しました。Runner production code、Job、API capability、SQLite11、toolchain、build／comparison経路は4.5で変更していません。Androidのdebug／release JVM test各147件、lintDebug、assemble、AndroidTest APK compileはPASSですが、接続端末がなくAndroid 16製品受入は`NOT_RUN`です。Runner testは4.5では`NOT_RUN`であり、Androidの回帰結果をRunner検証へ読み替えません。正本は[Phase 4.5契約](../reprodroid-project/docs/design/phase-4-release-check-contract.md)、[ADR-0023](../reprodroid-project/docs/adr/0023-phase-4-scheduled-release-discovery-and-notifications.md)、[実装記録](../reprodroid-project/reports/2026/09/2026-09-06-phase-4-5-implementation.md)です。

**Phase 4 現在地（2026-09-05）:** SQLite11へ4.4のgeneric Docker build／comparisonを実装しました。loopback development opt-inの`generic-build@1`と`apk-comparison@1`は、catalog管理toolchain、Docker必須・HOST fallbackなし、独立A／B、dynamic discovery、raw 3軸保存、memory OOMだけの明示retryを提供します。4.3 installerは正当な相対symbolic linkをconfineして許可し、archiveの実行bitをcontent manifestへ束縛して復元します。空store導入では9 artifactの`VERIFIED`とRunner再起動後のinventory復元を確認しました。JVM testは130件（pass 114、明示opt-in skip 16、failure 0）です。generic Android + Docker Build A/B製品E2Eは利用者指示により実施しておらず、機能成功・Reproducible判定の証拠ではありません。正本は[ADR-0021](../reprodroid-project/docs/adr/0021-phase-4-generic-build-sandbox-and-comparison.md)と[4.4実装記録](../reprodroid-project/reports/2026/09/2026-09-05-phase-4-4-implementation.md)です。

ReproDroid AndroidアプリからJobを受け、模擬ビルドまたはallowlist登録済みAndroid OSSの実ビルドを実行するPC側常駐プロセスです。

## 現在の状態

Phase 2Bの固定release build profileとAndroid比較E2Eに対応しています。Phase 2Cのtrust／update／install／settingsと、Phase 2Dの独立再ビルド／APK高度比較はAndroid側の責務です。永続schemaはSQLite v12、Phase 3Eの新規buildはprivate Manifest v4／public v3です。API v1は既定で維持し、API v2は明示的なdevelopment opt-inまたはpaired HTTPSで提供します。

Phase 3A の Runner 実装として、private Build Environment Manifest schema v2、recipe 固定 SDK / Build Tools package の事前検証、integrity / redaction 済み public projection endpoint を API v1 に additive に追加しています。Phase 3Bではfixed recipeへ`none`／`lockfile`／`lockfile_offline`を追加し、lock modeの`buildRoot/gradle.lockfile`をpre-build／post-buildで検査します。Phase 3Cではfixed recipeへtyped determinism blockを追加し、recipe literalの`SOURCE_DATE_EPOCH`、Gradleの`--no-build-cache`、`C.UTF-8`の`LANG`／`LC_ALL`をGradle processと子processだけへ適用します。effective値はSQLite v6、Job API、private Manifest v3／public v2へ保存し、historical private v2／public v1を未設定値に限って読み取ります。Phase 3Dではdetached checkout後・Wrapper／Gradle前のin-process static source scan、条件付きreview gate、SQLite v7、bounded detail／continue APIを実装しました。既存MicroG-RE recipeはpinning `none`、determinism未設定のままです。3Eは固定profileのopt-in Docker executor、SQLite v8、private v4／public v3を実装しました。raw comparison / trust を Runner に移さず、Build A / Build B の独立 Job / RCE／scan review境界も維持します。正本は [Phase 3 roadmap](../reprodroid-project/docs/design/phase-3-roadmap.md)、[ADR-0013](../reprodroid-project/docs/adr/0013-build-environment-manifest-public-api.md)、[ADR-0014](../reprodroid-project/docs/adr/0014-dependency-pinning-recipe-contract.md)、[ADR-0015](../reprodroid-project/docs/adr/0015-recipe-determinism-options.md)、[ADR-0016](../reprodroid-project/docs/adr/0016-pre-build-static-source-scan.md)です。

- `127.0.0.1:8080`へbindするKtor HTTP API v1
- SQLiteへ永続化する単一workerの非同期Jobキュー
- 外部プロセスを起動しない模擬成功・模擬失敗
- 差分取得できるログと模擬APKメタデータ
- 起動時に実行途中のJobを`INTERRUPTED`へ移す復旧処理
- GitHub ref解決、commit/RCE確認ゲート、detached checkout
- Gradle公式checksumによるdistribution/Wrapper JAR検査
- 固定task実行、timeout/cancel、APK検出、Build Environment Manifest、監査ログ
- API、成功・失敗、cancel、再起動永続化、安全ゲートの自動テスト
- build workspaceとは別の専用artifact領域へのAPK保存
- `SUCCEEDED` Jobの登録済みAPKだけを返すcontent endpoint
- 配信前のpath confinement、symbolic link、size、SHA-256再検査
- APK MIME type、Content-Length、SHA-256由来ETag
- repositoryとrevisionを組にしたexact recipe allowlist
- API／SQLiteへ永続化するrecipe ID、variant、Java major
- recipe別build JDKのpath／major検査と固定Java executableによるWrapper起動
- recipe固定のdependency pinning modeとSQLite v5監査保存
- lockfileのcheckout confinement、non-symlink regular file、8 MiB上限、pre/post SHA-256一致検査
- `LOCKFILE_OFFLINE`に限定したGradle `--offline`固定option
- recipe固定のepoch／Gradle Build Cache policy／`C.UTF-8` localeとSQLite v6監査保存
- Gradle processと子processだけへの決定性option適用、locale availability preflight
- detached checkout後・SDK／Wrapper／Gradle前のfixed in-process source scanner
- `.git`除外、symlink非追跡、strict UTF-8／Unicode path integrity、resource／timeout上限のfail-closed検査
- finding 1件以上で停止する`AWAITING_SCAN_REVIEW`とJob／digest単位のcontinue gate
- canonical scan result、detector count、finding、review bindを保存するSQLite v7
- durable operation、retention hold、storage reservation、availability、manual cleanup、toolchain installation／inventory／license／removal intentを保存するSQLite v10
- NOFOLLOW実測、64 GiB Job／32 GiB toolchain budget、80% warning、64 MiB recovery reserve
- strict JSON、JCS request hash、preview token再検証、item単位のpartial cleanup結果
- cleanup中断時の再起動照合と`RECONCILIATION_REQUIRED`による新規storage副作用停止

## Phase 4.2（development storage-retention API v2）

`REPRODROID_TRANSPORT_MODE=DEVELOPMENT_HTTP`はloopback bindだけで従来のlocal-development principalを有効化します。`PAIRED_HTTPS`は正確なendpointと初期化済みCAを要求し、全通常v1／v2 routeを認証します。両modeは排他的で、設定不明、非loopback development、鍵不整合では起動を拒否し、HTTPへfallbackしません。

4.2のRunner endpointはcapability／operation read、storage summary、JOB／ARTIFACT hold、限定reservation、manual cleanup preview／executeです。cleanup requestはpathを受け取らず、Runnerがowner root内の候補を列挙します。symlink、path escape、active／review待ちJob、sandbox cleanup `PENDING`、ACTIVE hold／reservation、preview後に変化したresourceを削除しません。automatic cleanup、log export、共有送信は含みません。

## Phase 4.3（trusted toolchain installation）

`toolchain-install@1`はLinux x86_64専用です。初期catalogはTemurin 21、Gradle 8.14.3／9.6.1／9.7.1、Android command-line tools 15859902、platform 36／37、build-tools 36.0.0／37.0.0の9 artifactを固定します。runtime remote catalog、repository指定URL、downloadしたinstaller実行、`sdkmanager`実行、HOST buildへの接続はありません。

導入物は`REPRODROID_STATE_DIR/toolchains`、途中物はinstallation ID単位の`toolchain-staging`だけへ置きます。archive size、展開bytes、entry count、path／link、component metadata、content manifestを検査し、同一filesystemのatomic move後にread-only化します。起動時はmanifestを実ファイルから再計算し、不一致を`RECONCILIATION_REQUIRED`にします。削除はinventory IDの期限付きpreviewと別の確定操作を必須とし、共有`JAVA_HOME`／`ANDROID_HOME`やcatalog外pathを受け取りません。

wire responseはdefault値を含む契約fieldを常に明示します。`apiVersion`、`foundationContractVersion`、`runnerVersion`、`capabilities`、各storage／cleanup responseの`schemaVersion`を省略しません。Android側は未知field、重複key、必須field欠落、別runnerIdをfail closedで拒否します。

## Phase 4.4（generic Docker build and comparison）

`REPRODROID_ENABLE_API_V2=true`、loopback bind、`REPRODROID_ENABLE_REAL_BUILDS=true`、`REPRODROID_BUILD_SANDBOX=DOCKER`の全条件を満たす時だけ、`generic-build@1`、`apk-comparison@1`、`codeberg-source@1`を広告します。`POST /v2/builds`はfull commit SHA、JCS化済み設定snapshot、利用者のRCE同意、expected APK base nameを受け、Job固有のsource／HOME／Gradle cache／containerで実行します。設定外のGradle、Java、Android SDK、NDK、CMake、任意image／mount／Docker socket／DinD／host network／port publishは受け付けません。

generic pathのGradle起動はcatalog管理のGradle launcherだけを使い、upstream wrapperを実行しません。Runner管理、clone、source scan／review、artifact検査、comparison、trust／install policyはhost側に残り、Gradle processと子processだけがcontainerへ入ります。containerのcleanup／bounded importを確認できなければ新規generic buildを停止します。hard disk／inode quotaと固定egress allowlistは現行4.4の対象外であり、bridgeをhost／LAN隔離の証拠として扱いません。

`POST /v2/comparisons`はOfficial-vs-A、Official-vs-B、A-vs-Bのraw `MATCH`／`DIFFERENT`／`INCOMPARABLE`を同じcomparison IDへ保存します。3軸`MATCH`、公式identity、trust／install policyが揃うことが`reproducible`の必要条件です。resource retryは独立evidenceのあるcontainer memory OOMだけに限定し、新しいA／B一組を一回だけ作成します。製品E2Eは未実施であり、これらの実装・fixture testを実public projectでのbuild成功へ読み替えません。

Phase 1EではMicroG-REの固定taskをRunner APIとAndroid UIから実行し、同一commitから同一size・SHA-256のAPKを生成した。content endpointからの取得、同じstate directoryでのRunner再起動後のJob／artifact／log cursor復元、Windows Android Emulatorからのdownloadと標準PackageInstaller E2Eまで確認済みである。詳細は[Phase 1E検証レポート](../reprodroid-project/reports/2026/08/2026-08-21-phase-1e.md)、履歴と最終状態は[Phase 1E再開・完了記録](../reprodroid-project/docs/handoffs/phase-1e-resume.md)を参照してください。

## Phase 3E（固定profileのopt-in実装）

Phase 3Eは[実測](../reprodroid-project/reports/2026/08/2026-08-30-phase-3e-feasibility.md)後に[ADR-0017](../reprodroid-project/docs/adr/0017-docker-build-sandbox-feasibility.md)と[実装契約](../reprodroid-project/reports/2026/08/2026-08-30-phase-3e-contract.md)をAcceptedとしました。検証範囲と制約は[最終受入記録](../reprodroid-project/reports/2026/08/2026-08-31-phase-3e-closeout.md)を参照してください。`REPRODROID_BUILD_SANDBOX=HOST|DOCKER`は実装済みで、未指定はHOST、空／未知値は起動時拒否です。`REPRODROID_ENABLE_REAL_BUILDS`は別gateです。DOCKERはMicroG-RE 6.1.4固定recipeだけをサポートし、imageの事前preloadと16 GiB以上の空きdiskが必要です。

Runner自体はhostで動作し、Job専用containerへDocker socketを渡しません。DinD／privileged／host network／host fallback／shared Gradle cacheはありません。bridgeはhost/LAN隔離の証明ではなく、hard Job disk quotaもありません。Job作成時のsnapshot、owner intent、実container inspectと回収、回収後のbounded safe importを使用します。回収未確認ならHOSTを含む新規REALは503で拒否し、SIMULATED／readを維持します。任意repositoryや別platformへの一般化、第三者attestationは行いません。

追加のruntime受入試験はoperator opt-inです。`REPRODROID_TEST_DOCKER=true`は固定imageを使用する無害なJava fixtureを起動し、そのfixture所有containerだけを回収します。`REPRODROID_TEST_DEVICE_DIRECTORY`は`null.apk`という実character deviceを持つ専用fixture directoryを指定し、読込み前拒否を試験します。これらが未指定のskipを実runtime検証済みと扱わないでください。

## Phase 2のRunner境界

Phase 2では、公式APKまたは開発者公開APKと更新情報をAndroidアプリ側で取得し、Android側で参照APK比較と更新候補判定を行います。Runnerは引き続きソース取得、allowlist済み実ビルド、Build Environment Manifest、ビルドartifact配信を担当し、公式APKを取得するAPIは追加しません。

Phase 2BはMicroG-RE `TAG 6.1.4`だけを許可する`defaultRelease` profileを追加しました。Runner本体はJDK 21で動かし、外部buildは検査済み`REPRODROID_JDK_18_HOME`のTemurin 18で`clean :play-services-core:assembleDefaultRelease`を実行します。`effectiveBuild`にはrecipe ID、variant、Java majorを追加し、Androidが対象同一性をfail closedで検査します。Runnerの`SUCCEEDED`はbuild成功だけを表し、配布元APKとの`MATCH`／`DIFFERENT`／`INCOMPARABLE`はAndroid側へ保持します。詳細は[ADR-0009](../reprodroid-project/docs/adr/0009-phase-2-reference-apk-and-update-boundary.md)と[ADR-0010](../reprodroid-project/docs/adr/0010-phase-2b-executable-apk-content-comparison.md)を参照してください。

Phase 2DではAndroidが同じtagでBuild A／Build Bの独立Jobを順に作成します。Runnerは各Jobでref解決、commit／host RCE確認、clone、per-job HOME／Gradle user home、Wrapper検査、固定recipe、artifact／Manifest保存を繰り返します。1回目の確認を2回目へ継承せず、batch build APIも追加しません。raw 3軸比較、APK全entry inventory、DEX構造比較、Manifest／resource table意味比較、trustはAndroid側の責務であり、Runnerへ公式APK取得、semantic parser、比較結果、Build Environment Manifest公開APIを追加しません。詳細は[ADR-0012](../reprodroid-project/docs/adr/0012-phase-2d-repeat-build-and-advanced-comparison.md)を参照してください。

この「公開 API を追加しない」は Phase 2D の境界である。Phase 3A は別 ADR により、private Manifest を直接配信せず redaction / integrity 検査済み projection を返す endpoint を追加した。既存 API v1 endpoint とRunner SQLite schema v4は変更していない。

Phase 2D最終E2Eは2026-08-26にfresh stateから再実行しました。MicroG-RE `6.1.4`のBuild A Job `df527f0e-dccf-4cb2-8cbf-f294cfeed611`とBuild B Job `94831a75-d879-4ae3-ad96-a53e4aabb686`は、別workspace／別Gradle user homeで同じfull SHA `d8df10ab687a1c1ca05221634cfa46bad262023a`を解決し、それぞれ個別のcommit／host RCE確認後に成功しました。両artifactは13,258,872 byte、SHA-256 `30de03caea3da52c9febbeebb5d7f0d3246811d288d81b522bb456da19e7b033`で一致しています。

この固定taskが生成するAPKは上流workflowの後段sign action前なのでunsignedです。Runnerはartifactのsize／SHA-256／配信完全性を保証しますが、比較用artifactへ署名を追加しません。Android側はcomparison専用経路でだけ扱い、通常のinstaller導線から分離します。

将来ReproDroid鍵でlocal comparison artifactを署名する案は候補として残しますが、Phase 2への採用は確定していません。Runnerは信頼済みrepositoryであってもGradle build scriptによる任意コード実行を許す境界にあるため、private keyを現行Runner process／build workspaceへ置きません。採用する場合は鍵の配置、分離、backup、rotation、signer continuityを別ADRで確定してから実装します。

## リポジトリ構成

- `reprodroid-runner`: 本リポジトリ。Runner実装
- `reprodroid`: Androidクライアント
- `reprodroid-project`: 全体設計、API、ADR、環境構築、作業レポート

全体設計は[ReproDroid設計書](../reprodroid-project/docs/design/Reprodroid%20Document.md)、API契約は[Runner API v1](../reprodroid-project/docs/api/runner-api.md)、実ビルド脅威モデルは[ADR-0003](../reprodroid-project/docs/adr/0003-real-build-security-model.md)を参照してください。

## 技術基盤

| 項目 | 内容 |
|---|---|
| 言語/フレームワーク | Kotlin/JVM + Ktor |
| package/group | `com.sanka1610.reprodroid.runner` |
| JDK | 21 |
| bind先 | `127.0.0.1` |
| port | `8080` |
| 永続化 | SQLite + ファイルストレージ |

## 実行モード

### `SIMULATED`

- Git、Java、Gradle等の外部プロセスを起動しない
- 成功または失敗を再現
- 段階的な進捗、ログ、模擬APKメタデータを返す
- UI、API、永続化、第三者テスト用

### `REAL_TRUSTED`

- デフォルト無効
- Runner起動時の明示設定が必要
- allowlist登録済みのpublic GitHub／Codeberg HTTPS URLだけを許可
- リポジトリ別レシピでbuild root、Gradle task、artifact pathを固定
- branch/tagをcommit SHAへ解決し、利用者確認後にdetached checkout
- Wrapperの配布ZIPとJARを公式checksumで検証
- timeout、cancel、監査ログを必須化

Androidから任意コマンドや任意Gradle引数を指定することはできません。

## セキュリティ上の注意

Gradle Wrapperを検証しても、`build.gradle(.kts)`やpluginはホスト上で任意コードを実行できます。allowlistはサンドボックスではありません。

初期の実ビルドは、利用者が選定した信頼済みリポジトリだけをWSL2ホスト上で実行する開発者向けアルファです。未登録の任意リポジトリを安全にビルドできるとは保証しません。

実ビルドには次の二重ゲートを要求します。

1. Runner起動時に`REAL_TRUSTED`を明示的に有効化
2. canonical repository URLがallowlistと一致

さらに、解決済みcommit SHA、実行task、RCE警告をAndroid側で確認するまでbuildを開始しません。

## Phase 4.7（Codeberg公開source）

generic buildのsource providerは、`github.com`と`codeberg.org`だけからなる閉じたhost registryです。両providerともHTTPS originのowner／repositoryだけを受け付け、credentials、port、query、fragment、余分なpath segment、encoded traversalは拒否します。入力は`https://{github.com|codeberg.org}/{owner}/{repository}`へcanonicalizeし、job、clone、Build Environment Manifestで同じ値を使用します。

`generic-build@1`のbody shapeやSQLite12は変更せず、Codeberg対応は別capability `codeberg-source@1`として広告します。このcapabilityはgeneric executionが有効なRunnerだけが広告し、Androidは未広告の旧Runnerに対してCodeberg sourceをfail closedで送信しません。commitはlowercaseのfull 40-character SHA-1だけを受け付けます。Runnerのprovider固有責務はsourceのclone／downloadに限定し、metadata、release、APKの発見・取得・選択はAndroid側の責務です。

製品経路では、`UnifiedPush/android-example`を固定recipeと異なるWrapper distributionとして`INVALID_DISTRIBUTION_URL`で拒否し、`droidify/client`を固定per-file上限超過の`SOURCE_SCAN_RESOURCE_LIMIT_EXCEEDED`で拒否しました。`qwerty287/ftpclient` 3.2.0はRCE確認、191 files／506434 bytesのsource scan、Gradle project discovery、SDK／Build Tools／Wrapper検査まで進み、旧配置ではDocker preflightを`SANDBOX_TOOLCHAIN_MISMATCH`で停止しました。これらは期待するfail-closedの負証拠であり、generic build成功やraw comparison成功ではありません。

その実行で判明したtoolchain境界として、JDKはdaemon child processに必要な`lib/jspawnhelper`をmetadata、非symlink file検査、実行bit復元、content manifestの対象へ追加しました。またAPI 37のcatalog archiveが提供するpackage directoryを`android-37.0`へ修正し、設定の整数`compileSdk=37`は変えず、inventory、host-side SDK検査、Docker read-only mount preflightの全箇所で同じ固定mappingを使用します。旧inventoryや`android-37` alias、自動license同意、自動SDK downloadへfallbackしません。新規Jobでは`docker-generic-v2`として`/tmp` 960 MiBを`noexec`、native launcher専用`/run/reprodroid-native` 64 MiBを`exec`で分離し、公開上限1 GiBと固定隔離境界を維持します。旧`docker-generic-v1`証拠はhistorical recordとして読めますが、同一comparison内でのprofile変化は拒否します。専用製品storeでは正規install／remove経路で`android-37.0`の導入と旧`android-37`の削除を確認しました。

最終製品実行では`qwerty287/ftpclient` 3.2.0の同一commitを独立workspace／独立scan reviewでBuild A／Bし、両Jobが成功しました。Androidのraw三軸はOfficial vs A `DIFFERENT`、Official vs B `DIFFERENT`、A vs B `MATCH`で、`reproducible=false`、`trustEligible=false`、`installEligible=false`として保存されました。途中のemulator停止では`adb reverse`消失による接続失敗を確認し、再設定後に復旧しました。別件のBuild Bログ追記競合はSQLiteのread-modify-writeを`IMMEDIATE` transactionで直列化して解消し、WALやschema変更は導入していません。

## Wrapper検査

実ビルド前に次を検査します。

- `gradle-wrapper.properties`の構文
- HTTPSの`distributionUrl`と許可ホスト
- Gradle distributionの公式SHA-256
- `gradle-wrapper.jar`の公式SHA-256
- レシピに記載したdistribution version、Wrapper JAR生成version、taskとの整合

対象リポジトリに`distributionSha256Sum`がない場合、レシピが明示的に許可した対象だけ、Runnerが公式checksumを取得して検証します。検査結果は`SUPPLIED_BY_RUNNER`として記録します。公式値で検証できない場合に続行するoverrideは実装しません。

## Jobと永続化

SQLiteへJob、request、resolved commit、recipe ID、variant、Java major、dependency pinning、effective determinism、scan result／review、状態、進捗、確認、エラー、ログ索引、artifactメタデータとcontent相対path、distribution/Wrapper検証結果、Manifestの相対pathとSHA-256を保存します。cloneしたソース、配信用に固定コピーしたAPK、ログ本体、Wrapper検査・環境・依存ファイルhashを含む`reprodroid-build.json`は専用state directoryへ保存します。これは private audit record であり、APIから直接配信しません。既存schemaを起動時にv9へtransactionalに移行し、旧REAL JobはHOST／LEGACY_HOSTとして保存します。Phase 1C以前のcontent pathを持たないartifactは自動推測せず、Jobのretryで再生成します。

Runner再起動時、実行途中だったJobは自動再実行せず`INTERRUPTED`へ移します。

主要な状態:

```text
CREATED
RESOLVING_SOURCE
AWAITING_CONFIRMATION
QUEUED
CLONING
SCANNING_SOURCE
AWAITING_SCAN_REVIEW
VERIFYING_WRAPPER
BUILDING
DISCOVERING_ARTIFACTS
SUCCEEDED
FAILED
CANCELLED
INTERRUPTED
```

## API

現行APIは次を提供します。

- Job作成・取得
- 実ビルド確認
- source scan detail取得とreview済みdigestへのcontinue
- ログ差分取得
- cancel・retry
- APK候補一覧
- artifact metadataとAPK content download
- health check

詳細は[Runner API v1](../reprodroid-project/docs/api/runner-api.md)を参照してください。

Phase 3A の `GET /v1/jobs/{jobId}/build-environment-manifest` は、成功した`REAL_TRUSTED` Jobのprivate Manifestをpath confinement、non-symlink regular file、32 MiB、監査SHA-256、strict JSON、schema / field integrity、redaction上限で検査し、public projectionだけを返します。private Manifest fileは配信しません。

Phase 3Bの`GET /v1/jobs/{jobId}`は、`effectiveBuild.dependencyPinning`を常に明示します。許可値は`NONE`、`LOCKFILE`、`LOCKFILE_OFFLINE`で、Android requestからは指定できません。lockfile path／contentとprivate pre/post SHA-256はpublic APIへ返しません。error codeとlegacy互換性は[Runner API v1](../reprodroid-project/docs/api/runner-api.md)を参照してください。

Phase 3Cの同endpointは`effectiveBuild.determinism`を常に明示します。epochはnullable非負Unix seconds、`noBuildCache`はboolean、localeはnullableの`C.UTF-8`だけです。新規private Manifest v3は同じeffective objectを必須とし、public schema v2へ投影します。SQLite値との不一致、v3 object欠落、設定済みJobに対する旧private v2は`BUILD_MANIFEST_INVALID`です。`SOURCE_DATE_EPOCH`の存在は全build toolによる利用を、`--no-build-cache`はGradle Build Cache以外のcache無効化を、locale固定は再現性を保証しません。

Phase 3Dは`GET /v1/jobs/{jobId}`へcompact `sourceScan` summaryを加え、`GET /v1/jobs/{jobId}/source-scan`で最大5,000件のdetector count／findingを返します。`POST /v1/jobs/{jobId}/source-scan/continue`は保存済みresult digestへreviewをbindし、同じclean workspaceのJobだけを再開します。source本文、snippet、absolute path、workspace、HOME、environmentは公開しません。SQLiteからの復元時はcanonical bytesを再構成し、保存bytesとSHA-256の両方が一致しなければ`SOURCE_SCAN_INVALID`でfail closedにします。

2026-08-29のPhase 3D fresh-state E2Eでは、MicroG-RE `6.1.4`のBuild A／Bを別Job・別workspace・別RCE確認・別scan reviewで実行しました。両scanは1,071 files／3,318,959 bytes、binary skip 0、symlink skip 1、121 findings、同一result SHA-256 `a2ac7ddd78fbb17fcbb716b6076459368bad404a5f985cf6cbcf24ca9fa5c92c`でした。両Jobはreview後に成功し、13,258,872 byte／SHA-256 `30de03caea3da52c9febbeebb5d7f0d3246811d288d81b522bb456da19e7b033`のAPKを生成しました。scan findingはraw comparison、trust、update、install policyを変更していません。

## 初期テスト対象

主対象は[MorpheApp/MicroG-RE](https://github.com/MorpheApp/MicroG-RE)です。暫定レシピと確認済み構成は[テスト対象文書](../reprodroid-project/docs/test-targets/microg-re.md)に記録しています。

同リポジトリは2026-08-20時点でGradle 8.14.3を使用しますが、`distributionSha256Sum`を記載していません。Runnerによる公式checksum補完を明示的に許可し、検証できなければ拒否します。

## bindと接続

デフォルトは`127.0.0.1:8080`です。

```bash
./gradlew run
```

永続データは既定で`~/.local/state/reprodroid-runner`へ保存します。上書き可能な設定は次のとおりです。

| 環境変数 | 既定値 | 用途 |
|---|---|---|
| `REPRODROID_STATE_DIR` | `~/.local/state/reprodroid-runner` | SQLite、ログ、後続Phaseの成果物 |
| `REPRODROID_HOST` | `127.0.0.1` | bind先 |
| `REPRODROID_PORT` | `8080` | port |
| `REPRODROID_ENABLE_REAL_BUILDS` | `false` | `REAL_TRUSTED`ホスト実行の危険受容 |
| `REPRODROID_ENABLE_API_V2` | `false` | loopback限定のdevelopment API v2 storage-retention |
| `REPRODROID_JDK_18_HOME` | なし | MicroG-RE `6.1.4` release profile専用JDK 18 |
| `REPRODROID_ALLOW_UNAUTHENTICATED_NON_LOOPBACK` | `false` | 非loopback bindの危険受容 |

非loopbackへbindする場合は`REPRODROID_ALLOW_UNAUTHENTICATED_NON_LOOPBACK=true`による明示的な危険受容が必要です。認証は未実装であるため、通常の利用では指定しないでください。

Androidからは開発用のADB reverseを使用します。

```bash
adb reverse tcp:8080 tcp:8080
```

認証機構を実装するまでは、無認証HTTPをLANへデフォルト公開しません。非loopback bindを用意する場合も、明示設定と警告を必須にします。

## 環境構築

共通スクリプトは次にあります。

```bash
../reprodroid-project/scripts/setup-env.sh
```

JDK 21.0.12.1+1、JDK 18.0.2.1+1、Android SDK API 36、platform-tools、固定したbuild-tools 36.0.0、emulator、system image、SDKライセンスを扱います。JDKはユーザー領域へ導入されるため、実行前にスクリプト末尾の`JAVA_HOME`と`REPRODROID_JDK_18_HOME`を現在のshellへ設定してください。

## ビルド・検証

基本コマンド:

```bash
./gradlew test
./gradlew build
./gradlew run
```

Phase 1Dでは`./gradlew test`と`./gradlew build`で、既存のHTTP API、SQLite、模擬成功・失敗、cancel、安全ゲートに加え、APK content、transfer header、保存後改ざん拒否、schema v3 migrationを検証します。Phase 1E完了時に`./gradlew test build --rerun-tasks`を実行し、8 actionable tasksすべてexecuted、`BUILD SUCCESSFUL`を確認しました。実ビルドを有効化する例:

```bash
REPRODROID_ENABLE_REAL_BUILDS=true \
REPRODROID_JDK_18_HOME="$HOME/.local/share/reprodroid/jdk-18.0.2.1+1" \
./gradlew run
```

起動時設定だけではbuildを開始しません。AndroidまたはAPIから、Runnerが解決したcommit SHAとRCEリスクをJob単位で確認する必要があります。

## Phase 4.6 secure Runnerのローカル操作

以下は4.6作業ブランチの操作入口です。実行済みの検証範囲・残件は[4.6実装記録](../reprodroid-project/reports/2026/09/2026-09-08-phase-4-6-implementation.md)で管理し、この手順の存在だけを製品受入の証拠にしません。

最初に専用のstate directoryと正確な接続先を選びます。既存stateの`runnerId`を作り直したり、欠損した鍵を暗黙再生成したりしません。次はloopback／ADB用の例で、`/absolute/path/to/runner-state`を実際の専用パスへ置き換え、同じ設定をRunner用とCLI用の両方のterminalへ適用します。

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

`security-init`は未初期化stateへの一度の明示操作です。以後の起動では実行せず、引数なしの最後のコマンドだけを使います。HTTPSとHTTPは同時に開かず、paired構成で失敗してもHTTPへ戻りません。ADB接続は端末を選んだうえで`adb -s <serial> reverse tcp:8443 tcp:8443`を設定します。LANではbindとadvertised endpointを実際の正確なIP／DNSへ明示変更し、wildcard bind、firewallやrouterの自動変更、公開internetへの直接露出は行いません。

別terminalで、同じstate／transport設定を使って次のlocal CLI操作を行います。表中のIDは前段の出力を確認して指定します。

| コマンド引数 | 操作と境界 |
|---|---|
| `pairing-open` | 5分・1回限りのmanual payloadと同じ内容のfield一覧を表示。payloadとinvitation secretをlog／Issueへ貼らない |
| `pairing-list` | pending request、device label、endpoint、作成時刻、confirmation fingerprintを確認 |
| `pairing-approve <requestId>` | Android画面とfingerprintを照合した1要求だけを承認 |
| `pairing-reject <requestId>` | 1要求を拒否。既に終端の状態は書き換えない |
| `principals-list` | principalの状態とcredential ageの確認に必要な非秘密情報を表示 |
| `principal-revoke <principalId>` | 紛失端末等のprincipalを失効。Job、履歴、artifactを削除せず、active Jobを自動cancelしない |
| `adoption-preview <sourcePrincipalId> <targetPrincipalId>` | `local-development`または失効済みprincipalからactive principalへの所有権移行をpreview |
| `adoption-execute <previewId>` | 10分以内のpreviewを再照合し、競合・active／review／cleanup状態がない場合だけ一括移行して監査 |
| `security-change-endpoint` | paired Runner停止中に、設定済みの新endpoint向けleafを同じrootで発行。新設定で再起動し、Androidは新manual payloadで再pair |
| `security-rotate-root` | paired Runner停止中の明示的root交換。pinが変わり旧principal／pending invitationを失効させるため、全端末で再pairが必要 |

鍵は専用`security`ディレクトリ内でowner-onlyに保管し、root／leafの不整合や欠損はfail closedで止めます。同じrootのleaf更新は期限7日前から自動で行い、新規TLS接続へ適用します。Androidのlocal-deleteとRunnerでの失効は別操作です。再pairは新principalを作り、旧所有権・RCE同意・scan review・trust・install権限を自動継承しません。

暗号ライブラリのlicenseと配布時のnoticeは[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md)を参照してください。QR生成／scan、CAMERA permission、Google Play services、LAN管理APIは追加しません。

## Phase 4.6実装後のRunner境界・対象外

Phase 3Eの固定profile、Phase 4.1のAndroid登録、4.2のhistory／storage、4.3のtoolchain、4.4のgeneric Docker build／comparison codeを実装しています。4.5は[実装契約](../reprodroid-project/docs/design/phase-4-release-check-contract.md)と[ADR-0023](../reprodroid-project/docs/adr/0023-phase-4-scheduled-release-discovery-and-notifications.md)に従ってAndroidへ実装しましたが、Runner production code／SQLite／API capabilityは変更していません。4.5のscheduled／manual metadata checkはRunner停止・ADB reverseなしでも成立する設計で、Runner Job、toolchain導入、build／comparisonを起動しません。Runner testは今回`NOT_RUN`です。4.3は空storeから9 artifactを導入し、inventory、Runner restart復旧、manual removal cleanupまで確認しました。4.4はSQLite11、`generic-build@1`／`apk-comparison@1`、catalog-managed Gradle launcher、独立A／B、raw三軸、memory OOM限定retryを追加しています。[Phase 4 roadmap](../reprodroid-project/docs/design/phase-4-roadmap.md)、[4.5実装記録](../reprodroid-project/reports/2026/09/2026-09-06-phase-4-5-implementation.md)、[4.4実装記録](../reprodroid-project/reports/2026/09/2026-09-05-phase-4-4-implementation.md)を正本とします。Android 16の4.5製品受入とAndroid + Dockerの公開source二project Build A／B・比較E2Eは`NOT_RUN`であり、実provider経路、実build成功、Reproducibleの証拠ではありません。

4.6の具体値は[実装契約](../reprodroid-project/docs/design/phase-4-runner-connectivity-contract.md)に従ってSQLite12へ実装しました。`DEVELOPMENT_HTTP`の無認証loopbackと`PAIRED_HTTPS`は排他的で、旧`REPRODROID_ALLOW_UNAUTHENTICATED_NON_LOOPBACK=true`は受け付けません。paired modeの通常v1／v2 endpointはすべて認証し、管理操作はlocal CLIに限定します。旧principalのownershipは自動移行せず、preview、再検証、atomic execute、監査を伴う明示操作だけで移行します。44 PASS／0 PARTIAL／0 NOT_RUNの受入結果は[4.6実装記録](../reprodroid-project/reports/2026/09/2026-09-08-phase-4-6-implementation.md)を正本とします。

汎用buildはDocker必須、HOST fallbackなしで、4 CPU、memory 8 GiB、swapなし、PID 1,024、tmpfs合計1 GiB、詳細log 64 MiB、Gradle workers最大2、Runner実行枠1、A／B各60分を固定します。新規Jobの`docker-generic-v2`は`/tmp` 960 MiBを`noexec`、SQLite JNIなどの展開先`/run/reprodroid-native` 64 MiBだけを`exec`とし、`-Dorg.sqlite.tmpdir`、mount flags、native実行成功と`/tmp`実行拒否をGradle前に検証します。既存`docker-generic-v1`のcanonical bytes／profile hashは変更せず読み取り可能です。Runnerがcatalog digestで検証済みのGradle launcherだけをcontainer内で起動し、repository Wrapper、Docker socket、DinD、host network、port publish、任意image／mountを拒否します。hard disk／inode quotaと固定egress allowlistは現行4.4の対象外であり、bridgeやtmpfsをこれらの実効制御の証拠にしません。

不足JDK／Gradle／Android toolsの専用store導入、Android history／予約・hold／手動cleanup、HTTPS／pairing／認証、Runner／Job log export、WSL／Linux向け配布準備を含めます。資源不足再試行は既定OFF・限定許可付きで新A／Bを作り、scan review・全体budget・cleanup確認を省略しません。定期release確認からbuildを始めず、参照APK取得と比較はAndroidが所有します。4.6は利用者承認により検証後のローカル`develop`統合までを対象とし、push／`main`統合／公開は行いません。

### Phase 3 の対象外

- allowlist外リポジトリの実ビルド
- HTTPS/WebSocket
- LANへの無認証デフォルト公開
- 公式APKとの比較（Phase 2ではAndroid側で実施）
- split APK、APKS、AAB
