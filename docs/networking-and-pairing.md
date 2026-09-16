# Networking and pairing

Runnerには、loopback限定のdevelopment HTTPと、manual pairingを使用するpaired HTTPSがあります。

## Development HTTPとADB reverse

development HTTPは`127.0.0.1`または`::1`にだけbindします。

```bash
export REPRODROID_TRANSPORT_MODE=DEVELOPMENT_HTTP
export REPRODROID_HOST=127.0.0.1
export REPRODROID_PORT=8080
reprodroid-runner
adb reverse tcp:8080 tcp:8080
```

ADB reverseはAndroid端末からPCのloopbackへ到達するtransportです。端末credential、root pin、paired principalは作成しません。

## Paired HTTPS

paired modeは次を要求します。

- wildcardではないbind address
- 設定portを含むexact HTTPS origin
- query、fragment、任意pathを持たないadvertised endpoint
- 初期化済みのRunner root/leaf certificate
- Androidが保持するroot public-key pinと端末別credential

設定不備、pin不一致、credential不正、失効principalではHTTPへfallbackしません。

## Manual pairing

1. PCで`pairing-open`を実行します。
2. 5分・1回限りのinvitationをAndroidへ入力します。
3. Androidが`POST /pairing/v1/requests`でpending requestを作成します。
4. PCの`pairing-list`とAndroid画面のconfirmation fingerprintを比較します。
5. 一致したrequestを`pairing-approve <requestId>`で承認します。
6. Androidは承認済みrequestを取得し、credentialをprivate storageへ保存します。

一致しないrequestは`pairing-reject <requestId>`で拒否します。invitationを作成しただけでは通常APIの利用権限は付与されません。

## Linux LAN

Android端末から到達できる固定hostまたはIPを使用します。

```bash
export REPRODROID_HOST=192.0.2.10
export REPRODROID_PORT=8443
export REPRODROID_ADVERTISED_ENDPOINT=https://192.0.2.10:8443
```

`security-init`または`security-change-endpoint`で、advertised endpointのhostに対応したleaf certificateを用意します。OS firewallではTCP 8443を必要なlocal networkだけに許可します。Runnerをpublic internetへ直接公開しません。

## WSL2

### LoopbackとADB reverse

最初の接続確認ではRunnerをWSL2の`127.0.0.1`へbindし、Windows側の`adb reverse`から利用します。この経路ではWindows firewallのinbound ruleは通常不要です。

### Mirrored networking

LAN上のAndroid端末から直接接続する場合は、WSL2 mirrored networkingでAndroidから到達できるaddressを確認し、そのaddressをbindとadvertised endpointへ設定します。Windows firewallの変更は管理者操作を伴うため、接続元networkとportを限定します。

NAT address、mirrored address、Windows host addressを混在させるとcertificate SANまたはendpoint pinが一致しません。Androidが入力するoriginを一つに固定します。

## Endpoint変更とroot交換

- 同じRunner identity/rootのままhostを変更する: `security-change-endpoint`
- root自体を交換する: `security-rotate-root [host]`

root交換後は既存client credentialとpinを引き継がず、全Android端末を再pairします。
