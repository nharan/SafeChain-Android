# Security Audit — `cbf3a7772a6..f9cd0f14dd1` (2026-10-23)

Scope: every commit from `cbf3a7772a6` to `HEAD` (6 commits, 64 files), plus the code those commits did not
touch but that still affects key safety and privacy. Method: full diff review, Card SDK bytecode inspection
(`tangem-sdk-kotlin` `releases-6.4-672`), merged-manifest inspection, codebase-wide grep for remaining
network/telemetry paths and key-material handling.

**Bottom line.** Nothing in these commits weakens the private-key path itself: signing, the card's key
generation, and BackupService are functionally the same as before (`BackupServiceHolder` builds the same
`BackupRepo` + `BackupService` as the SDK's `init`, and swaps only the storage to RAM). But the privacy goal
is far from met. The app still tells Tangem, automatically and on every launch, *which card* you hold, and
which wallet IDs you own. Several claims in the previous audit notes do not match the code. The largest
risk to the keys themselves is the hot-wallet cloud backup, which upstream ships and this fork leaves on.

---

## 1. Claims in the previous audit notes that are false or inaccurate

| Claim | Reality |
|---|---|
| **F-08** "Replaced `AttestationTask.Mode.Normal` with `Mode.Offline` in `DefaultTangemSdkManager` and `TangemSigner`" | **Not done in any commit.** No source file sets `attestationMode`. `DefaultCardSdkProvider.createConfig()` (`app/.../tap/data/DefaultCardSdkProvider.kt:226`) uses the SDK default, which is `Mode.Normal` (confirmed in `Config.class`). `AttestationTask.runOnlineAttestation` therefore runs on every scan and sends `cardId` + `cardPublicKey` to Tangem. The edits to those two files only removed the registration wrapper. |
| **F-06** "Registers `Application` context at startup" | Registered only in `DefaultOnboardingMultiWalletComponent.init`. In addition, `checkStatus()` **fails open**: a null context returns `isAirplaneModeOn = true` (`AirplaneModeGuardrail.kt:3078`). |
| **F-13** "Returns `NoBackup` when `cards.isEmpty()`" | It returns `RecommendedRescan` or `MandatoryRescan`. The behaviour is reasonable; the note is wrong. |
| **F-12/F-19** "Interceptor redacts sensitive headers" | True for the *display*, but the interceptor is observe-only. It never strips anything, so the "transparency" UI can make users think a leak is handled when it is not (see §2). |
| **F-20** "Transitive SDK services removed" | Firebase is still fully wired: `FirebaseInitProvider` and `ComponentDiscoveryService` with the Messaging, Installations, Sessions, RemoteConfig, Crashlytics (+NDK), Perf, ABT and Datatransport registrars are all present in the merged manifest. |
| NEAR/TON endpoint lists (`lava.build`, `1rpc.io`, `toncenter …/jsonRPC`) | The code uses `near.drpc.org`, `rpc.mainnet.near.org` and `toncenter.com/api/v2/`. |
| `SECURITY_HARDENING.md` §2 `isTangemIdentityEnabled = false` | The toggle is `isBackendAuthenticationEnabled`. |

---

## 2. Findings

Severity: **Critical** = key material can leave the user's control; **High** = an identifier tying a person to
their wallet/card is sent automatically; **Medium** = sent on explicit action, or a weaker at-rest/on-device
exposure; **Low** = defence-in-depth.

### Key material

| # | Sev | Finding | Location | Fix |
|---|---|---|---|---|
| K-1 | **Critical** | **Hot-wallet cloud backup.** The mnemonic is encrypted with a user password and uploaded to Google Drive. The same password is then saved to the device password manager (`TWI_1766_PASSWORD_MANAGER_ENABLED`, on in prod from 6.4). Both are usually tied to the same Google account, so taking over that one account exposes the seed. | `features/hot-wallet/impl/.../createcloudbackup/model/CreateCloudBackupModel.kt:296-309`, `DefaultHotWalletFeatureToggles.kt:17` | Remove cloud backup from this fork. At minimum, hard-disable saving the password. |
| K-2 | **High** | **Device-to-device transfer is not blocked.** `allowBackup="false"` does not stop D2D transfer on Android 12+, and `data_extraction_rules.xml` excludes only `appsflyer-data`. The hot-wallet encrypted blobs, the user-wallet store and preferences can be copied to another device. | `app/src/main/res/xml/data_extraction_rules.xml` | Exclude `root`, `file`, `database`, `sharedpref` and `external` with `path="."` under both `<cloud-backup>` and `<device-transfer>`. |
| K-3 | Medium | No `FLAG_SECURE` on the cloud-backup password screen, which has a show/hide toggle. | `createcloudbackup/DefaultCreateCloudBackupComponent.kt:24-34` | `DisableScreenshotsDisposableEffect()` |
| K-4 | Medium | `FLAG_SECURE` is per screen only. Balances, addresses and WalletConnect requests appear in recent-apps thumbnails and screen recordings. | `core/ui/.../security/DisableScreenshots.kt` | Set `FLAG_SECURE` app-wide in `MainActivity`. |
| K-5 | Medium | The airplane guardrail fails open (null context → "offline"). It checks only at the moment of the tap, and it does not cover any hot-wallet flow: create, import, reveal/back up phrase, cloud backup/restore, or hot→card upgrade. | `AirplaneModeGuardrail.kt`; `features/hot-wallet/**`; `MultiWalletUpgradeWalletModel.kt:90` | Fail closed. Register the context in `TangemApplication`. Apply the guard to the hot-wallet flows if the policy is meant to be consistent. |
| K-6 | Low | The seed-import and passphrase fields keep the long-press Copy/Cut menu. The copy is not marked sensitive and is never auto-cleared. The passphrase is unmasked. | `AddExistingWalletImportContent.kt:84-165`, `MultiWalletSeedPhraseImport.kt:96,153` | `DisableContextMenu`, `PasswordVisualTransformation` |
| K-7 | Low | `IME_FLAG_NO_PERSONALIZED_LEARNING` is never set on the seed fields. Some third-party keyboards ignore `KeyboardType.Password`. | same | An EditText with the flag, or a warning to the user. |
| K-8 | Low | The mnemonic and passphrase are kept as `String` in the UI state and in the hot→card upgrade, and are never wiped. | `MultiWalletSeedPhraseUM.kt`, `SeedPhraseState.kt`, `MultiWalletUpgradeWalletModel.kt:103` | Null them out in `onDestroy`; use `CharArray` and fill it after use. |
| K-9 | Low | Backup RAM state (access code and encrypted backup data) lingers for the process lifetime if onboarding is left by a path that skips `clearActiveSession()`. The legacy-purge only deletes through whichever `SecureStorage.create()` implementation is current (V1 vs V2). | `BackupServiceHolder.kt` | Also clear in `MainActivity.onDestroy`, and purge both storage versions. |
| K-10 | Low | WalletConnect `eth_sign` shows `hexToAscii(raw)`, which is unreadable for a hash. The prefix makes it safe against signing a raw transaction, but the user cannot see what they are signing. | `WcEthNetwork.kt:137-146` | Reject `eth_sign`, or show the hex with a warning. |

### Identifiers sent automatically to Tangem / Google

| # | Sev | Finding | Location | Fix |
|---|---|---|---|---|
| N-1 | **High** | **`card_id` + `card_public_key` headers on every `api.tangem.org` call** (quotes, coins, geo, markets, providers, …) and on every `yield.tangem.org` call. This alone links IP + card to every app session. | `store/datasource/.../config/TangemTech.kt:48`, `grow/datasource/.../config/YieldSupply.kt:73`, `AuthRequestHeaders.kt:14-15` | Remove `cardAuthHeader` from both configs. |
| N-2 | **High** | **Online card attestation** on every scan (the F-08 finding above, still open). | `DefaultCardSdkProvider.kt:226` | `attestationMode = AttestationTask.Mode.Offline`. Then check that backup-card linking still works (the issuer certificate may need a one-off online fetch, which the backup screen already prompts for). |
| N-3 | **High** | **FCM token fetched on every launch.** The stubbed `createApplicationId` returns success, so `SendPushTokenUseCase` still calls `FirebaseMessaging.getInstance().token`. That creates a Firebase Installation and an FCM registration with Google. The Huawei flavor calls the same method. | `MainViewModel.kt:106,402-416`, `app/src/google/.../FirebasePushNotificationsTokenProvider.kt:12` | Stub the token provider to return nothing. Set `firebase_messaging_auto_init_enabled=false`. Remove `FirebaseInitProvider` via `tools:node="remove"`, or drop the Firebase dependencies. |
| N-4 | **High** | **walletId sent automatically** by many unhardened paths: `GET notification-preferences/{walletId}` on the main screen; `v1/banner/displays?walletId=` for all wallets; `v1/referral/{walletId}`; `/v2/promotion?walletId=` and yield-boost status; `POST v1/address-books/sync` with all walletIds; `wallets/{walletId}/joint-accounts`. | `WalletModel.kt:178,223,918`, `PromoBannersBlockModel.kt:124`, `ReferralBlockModel.kt:57`, `ShouldShowYieldBoostMainBannerUseCase.kt:25`, `DefaultAddressBookRepository.kt:144`, `AccountListCryptoCurrenciesFetcher.kt:48` | Stub each repository locally, the same way `DefaultWalletsRepository` was stubbed. |
| N-5 | **High** | **Express `user-id` = walletId** on `getAssets` during every balance refresh, plus express/onramp history deltas. | `DefaultExpressServiceFetcher.kt:72`, `TangemExpressApi.kt`, `OnrampApi.kt`, `DefaultExpressHistoryRepository.kt` | Send a random per-install (or per-request) id instead of the walletId; fetch assets only when the user opens swap. |
| N-6 | Medium | Device model, OS, language and timezone headers on all Tangem gateways. | `core/remote/.../RequestHeader.kt:23-41` | Drop `device` and `timezone`. |
| N-7 | Medium | Crashlytics still initializes and stores crash reports on disk (not uploaded while collection is disabled). Firebase Perf traces are called directly (inert while deactivated). | manifest; `crashlytics-ndk` runtimeOnly | Remove the Crashlytics/Perf dependencies. |
| N-8 | Medium | Addresses go automatically to third parties: StakeKit and P2P on balance refresh; Tangem-run RPC nodes for Alephium, Chia and ICP; the new public NEAR/TON RPCs. This is inherent to a light wallet, but should be documented. | `providers_order.json`, `MultiStakingBalanceFetcher` | Document it. Optionally gate staking fetches behind opening the staking screen. |
| N-9 | Medium | Explicit-action paths that send the walletId: swap/onramp, `bind-wallets-by-code` (promo deeplink / AppsFlyer data), `v2/transaction-events`, Hedera `user-network-account`. | see the network agent report | Acceptable on action; remove `bind-wallets-by-code`. |
| N-10 | Low | `HqTransparencyInterceptor` shows unredacted URL paths in toasts and the log dialog (paths contain walletIds and addresses), and labels requests that failed upstream as "BLOCKED". | `HqTransparencyInterceptor.kt` | Redact path segments that look like ids; say "FAILED", not "BLOCKED". |

### Functional regressions introduced by the commits

| # | Finding | Location |
|---|---|---|
| R-1 | `isValidUrl` now rejects every non-`https://` URL. Previously `wss://` providers were allowed, so chains that rely on WebSocket/Electrum providers lose them. | `BlockchainProvidersResponseMerger.kt:93` |
| R-2 | `activatePromoCode` now always fails, and wallet name changes no longer sync remotely. Both are intended, but the user gets no explanation. | `DefaultWalletsRepository.kt` |
| R-3 | If the process dies mid-backup, the cards are left in `CardLinked` and must be reset (the stated trade-off). `primaryCard.linkingKey` is a public key, so stripping it from disk gains little; consider reverting that change if resume matters. | `DefaultOnboardingRepository.kt`, `ScanResponseAsStringSerializer.kt` |

---

## 3. Verified clean

- No `FileLogWriter`, `NetworkLogsSaveInterceptor` or Chucker is installed anywhere. The Card SDK APDU logger is not registered.
- No AppsFlyer, Customer.io or Amplitude initialization. `Analytics` drops every event. The registration launcher and backend auth are disabled (`Disabled*` implementations).
- `BackupServiceHolder` is equivalent to the SDK's `BackupService.init` apart from the storage (bytecode-checked). Buffers are zeroized on overwrite and delete.
- No mnemonic, passphrase or access code appears in any log call. The cloud-backup password is a `CharArray` and is wiped. Nothing reaches `onSaveInstanceState`.
- Release builds are not debuggable. Only `MainActivity` is exported. The clipboard manager supports `EXTRA_IS_SENSITIVE`.
- Solana `signMessage` rejects serialized transactions.

## 4. Recommended order of work

1. **K-1, K-2**: remove the hot cloud backup and password saving; lock down data-extraction rules.
2. **N-1, N-2, N-3**: strip the card headers, switch attestation to Offline, kill FCM/Firebase init.
3. **N-4, N-5, N-6**: local stubs for the remaining walletId paths, no walletId in Express headers, drop the device headers.
4. **K-3 to K-5**: app-wide `FLAG_SECURE`; make the guardrail fail closed.
5. Correct `SECURITY_HARDENING.md` and the previous audit notes (§1).
6. Then everything rated Low.

Not verified: how the Hot SDK encrypts keys at rest (Keystore-bound vs a key derived from the access code). This
decides how serious K-2 is in practice and should be checked next. No build or tests were run for this audit.
