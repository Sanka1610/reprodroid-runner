# ビルドとツールチェーン

## Buildの開始条件

汎用ビルドを使う場合は、Runner起動前に次を設定します。

```bash
export REPRODROID_ENABLE_REAL_BUILDS=true
export REPRODROID_BUILD_SANDBOX=DOCKER
```

各設定の既定値は[設定](configuration.md)、実行環境と制限は[セキュリティ](security.md#build-isolation)に記載しています。固定MicroG-REレシピは`REPRODROID_JDK_18_HOME`でJDK 18を指定します。レシピは[BuildRecipes.kt](../src/main/kotlin/com/sanka1610/reprodroid/runner/BuildRecipes.kt)の`BuildRecipeRegistry`で管理します。

## ソースとスキャンの確認

Jobのリポジトリと解決済みコミットSHA、レシピまたはビルド設定を確認します。ビルド前スキャンで指摘がある場合は、検出内容と保存されたスキャンハッシュを確認して継続を判断してください。検出対象は[ApiModels.kt](../src/main/kotlin/com/sanka1610/reprodroid/runner/ApiModels.kt)の`SourceScanDetectorId`、要求形式は[Source scan continue](api/jobs-v1.md#source-scan-continue)を参照してください。

## ツールチェーンの導入

Androidのツールチェーン画面でカタログを取得し、必要構成を計画します。ライセンス・ハッシュ・ダウンロード容量・展開容量を確認して導入してください。導入結果を確認してからJobを作成します。削除時はプレビューで参照中・保護対象の構成を確認します。

カタログ、計画、導入・削除のフィールドは[Toolchain API](api/toolchains-v2.md)にまとめています。

## Build A／Bと比較

Androidから各ビルドを個別に承認します。作成した成果物とJob情報をAndroidへ返し、Androidで計算した比較結果を[Comparison API](api/builds-v2.md#comparisonを作成する)へ保存します。Runnerは保存済みのJob・構成・所有権と要求の整合性を検査します。

比較範囲とAndroidでの表示は[再現可能性の判定](https://github.com/Sanka1610/reprodroid/blob/main/docs/reproducibility.md)、メモリ不足時の再試行は[Resource retry](api/builds-v2.md#resource-retry)を参照してください。
