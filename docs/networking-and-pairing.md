# Networking and pairing

Runnerには、loopback限定のdevelopment HTTPと、手動ペアリングを使用するpaired HTTPSがあります。

## Development HTTPとADB reverse

development HTTPは`127.0.0.1`または`::1`にだけbindします。

```bash
export REPRODROID_TRANSPORT_MODE=DEVELOPMENT_HTTP
export REPRODROID_HOST=127.0.0.1
export REPRODROID_PORT=8080
reprodroid-runner
```

別ターミナルで`adb reverse tcp:8080 tcp:8080`を実行します。ADB reverseはAndroid端末からPCのloopbackへ到達するtransportです。端末認証情報、root pin、paired 接続主体は作成しません。

## Paired HTTPS

paired modeは次を要求します。

- wildcardではないbind address
- 設定ポートを含むexact HTTPS origin
- query、fragment、任意パスを持たないadvertised エンドポイント
- 初期化済みのRunner root/leaf 証明書
- Androidが保持するroot public-key pinと端末別認証情報

設定不備、pin不一致、認証情報不正、失効接続主体ではHTTPへfallbackしません。

## Manual pairing

PCの別ターミナルで、起動中Runnerと同じ保存先・接続設定を設定します。次は[導入手順](installation.md)のADB reverse構成を使う例です。版は展開済みのものへ変更してください。

```bash
RUNNER_VERSION=0.1.0-alpha02 # 展開済みの版
RUNNER="$HOME/.local/opt/reprodroid-runner-${RUNNER_VERSION}/bin/reprodroid-runner"
export REPRODROID_STATE_DIR="$HOME/.local/state/reprodroid-runner"
export REPRODROID_TRANSPORT_MODE=PAIRED_HTTPS
export REPRODROID_HOST=127.0.0.1
export REPRODROID_PORT=8443
export REPRODROID_ADVERTISED_ENDPOINT=https://127.0.0.1:8443
adb reverse tcp:8443 tcp:8443
"$RUNNER" pairing-open
```

1. 表示されたinvitationをAndroidのRunner認証画面へ入力します。
2. PCで`"$RUNNER" pairing-list`を実行し、Android画面と確認用フィンガープリントを照合します。
3. 一致する要求のIDを使って`"$RUNNER" pairing-approve <requestId>`を実行します。不一致なら`pairing-reject <requestId>`で拒否します。
4. Androidで承認結果を取得し、接続状態を確認します。

invitationの期限・回数・API要求の上限は[Pairing API](api/pairing.md#requestを作成する)を参照してください。invitationや端末の認証情報をログ・Issue・スクリーンショットへ転載しないでください。

## Linux LAN

例の`192.0.2.10`をAndroid端末から到達できる実際のホストまたはIPへ置き換えます。

```bash
export REPRODROID_TRANSPORT_MODE=PAIRED_HTTPS
export REPRODROID_HOST=192.0.2.10
export REPRODROID_PORT=8443
export REPRODROID_ADVERTISED_ENDPOINT=https://192.0.2.10:8443
```

`security-init`または`security-change-endpoint`で、advertised エンドポイントのホストに対応したleaf 証明書を用意します。OS firewallではTCP 8443を必要なlocal ネットワークだけに許可します。Runnerをpublic internetへ直接公開しません。

## WSL2

### LoopbackとADB reverse

最初の接続確認ではRunnerをWSL2の`127.0.0.1`へbindし、Windows側の`adb reverse`から利用します。この経路ではWindows firewallのinbound ruleは通常不要です。

### Mirrored networking

LAN上のAndroid端末から直接接続する場合は、WSL2 mirrored networkingでAndroidから到達できるaddressを確認し、そのaddressをbindとadvertised エンドポイントへ設定します。Windows firewallの変更は管理者操作を伴うため、接続元ネットワークとポートを限定します。

NAT address、mirrored address、Windows ホスト addressを混在させると証明書 SANまたはエンドポイント pinが一致しません。Androidが入力するoriginを一つに固定します。

## Endpoint変更とroot交換

- 同じRunner identity/rootのままホストを変更する: `security-change-endpoint`
- root自体を交換する: `security-rotate-root [host]`

root交換後は既存クライアント認証情報とpinを引き継がず、全Android端末を再pairします。
