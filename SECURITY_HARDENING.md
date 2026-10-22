# Security & Privacy Hardening Notes (Community Edition)

This repository is a privacy- and security-hardened fork of the official Tangem Android application. It is designed for users who want verifiable assurance that their seed phrases, access codes, encrypted backup payloads, `walletId` identifiers, and multi-card cluster topologies are not sent to Tangem's backend or persisted to flash storage during onboarding and wallet operations.

> [!IMPORTANT]
> **License Notice**: Upstream Tangem distributes this repository under a custom source-available license (`LICENSE` — *"NOTICE: THIS IS NOT AN OPEN SOURCE LICENSE"*), which permits personal/non-commercial modification and security research, but prohibits commercial redistribution or competing commercial products without permission.

---

## 1. Removal of Third-Party Telemetry, Analytics, Crash Reporting & Push Registration

### What Changed
- **App Initialization & Analytics Router**: Stripped all analytics and crash-reporting SDK initializations and event dispatching in `app/src/main/java/com/tangem/tap/TangemApplication.kt`, `app/src/main/java/com/tangem/tap/MainActivity.kt` (including `AppsFlyerLib` deep-link callbacks), and `core/analytics/src/main/java/com/tangem/core/analytics/Analytics.kt` (AppsFlyer, Amplitude, Firebase Analytics, Firebase Crashlytics, Google Analytics, Customer.io).
- **Push Token & Device Registration Disabled (`TangemPushNotificationService.kt` & `DefaultPushNotificationsRepository.kt`)**:
  - Turned `TangemPushNotificationService.onNewToken()` and `onMessageReceived()` into no-ops so Firebase Cloud Messaging tokens are never forwarded to `CustomerIOFirebaseMessagingService` or dispatched.
  - Turned `DefaultPushNotificationsRepository.createApplicationId()`, `sendPushToken()`, and `getEligibleNetworks()` into local no-ops so device metadata (device model, OS, language, timezone, push token) and `walletIds` are never sent to Tangem's push backend.
- **OpenTelemetry & A/B Testing Disabled**: Replaced `app/src/main/java/com/tangem/tap/common/analytics/handlers/opentelemetry/OpenTelemetryMetricsHolder.kt` with a no-op tracer (`TracerProvider.noop()`), disabled `OtelFeatureToggles.kt`, and bound a no-op A/B test manager in `core/abtests/src/main/kotlin/com/tangem/core/abtests/di/ABTestsManagerModule.kt`.
- **Android Manifest Lockdown**: Disabled background telemetry and push services, receivers, and content providers in `app/src/main/AndroidManifest.xml` (`firebase_analytics_collection_deactivated=true`, `firebase_crashlytics_collection_enabled=false`, disabled `FirebasePerfProvider`, `AppMeasurementService`, `AppMeasurementJobService`, `AppMeasurementReceiver`, and `TangemPushNotificationService`).

### Why
Upstream Tangem initializes multiple behavioral tracking, attribution, push-notification, and telemetry pipelines on startup. Even without leaking private keys, telemetry correlates a user's IP address, device fingerprint, app usage timestamps, and onboarding steps with third-party servers.

---

## 2. Eliminating Wallet Registration, Account Sync & Multi-Card Topology Calls to Tangem HQ

### What Changed
- **Replaced Remote Multi-Card Cluster Reporting (`POST /v1/wallets/cards`) with Local Backup Verification Records**:
  - Updated `data/wallets/src/main/java/com/tangem/data/wallets/DefaultWalletCardsBackupRepository.kt`, `data/wallets/src/main/java/com/tangem/data/wallets/store/PendingWalletCardsBackupStore.kt`, and `features/onboarding-v2/impl/src/main/kotlin/com/tangem/features/onboarding/v2/multiwallet/impl/common/WalletCardsBackupReporter.kt` so that each finalized card in a backup set is recorded strictly locally in `PendingWalletCardsBackupStore` (with `usedSeed = false`) without ever calling `tangemTechApi.postWalletCards()` or `tangemTechApi.getWalletCards()`.
  - Updated `domain/wallets/src/main/java/com/tangem/domain/wallets/usecase/GetWalletBackupIntegrityUseCase.kt` to require verified per-card records (`cards.size >= 1 + localBackupStatus.cardCount`, all in `ACTIVE` status) before returning `WalletBackupIntegrity.FullyActivated`. When verified per-card records are missing (`cards.isEmpty()`), it returns `RecommendedRescan` (for `Active` / `NoBackup`) or `MandatoryRescan` (for `CardLinked`) rather than reporting full activation from unverified status metadata.
- **Blocked All Legacy & V1 Wallet Registration (`DefaultWalletServerBinder.kt`, `DefaultWalletsRepository.kt`, `SyncWalletWithRemoteUseCase.kt`)**:
  - Turned `DefaultWalletServerBinder.bind()` into a purely local success response without calling `appsFlyerRepository.getConversionData()` or `tangemTechApi.createWallet()`.
  - Made `DefaultWalletsRepository.setWalletName()`, `upgradeWallet()`, `getWalletInfo()`, `getWalletsInfo()`, `associateWallets()`, `activatePromoCode()`, and `SyncWalletWithRemoteUseCase` strictly local so `walletId` and `bitcoinAddress` are never registered or synced to `api.tangem-tech.com`.
- **Replaced Remote Account & Token List Syncing with Local Persistence (`DefaultWalletAccountsFetcher.kt`, `DefaultAccountsCRUDRepository.kt`, `UserTokensSaver.kt`)**:
  - Replaced remote `walletAccountsApi` calls in `DefaultWalletAccountsFetcher` and `DefaultAccountsCRUDRepository` with local `AccountsResponseStore`, `ArchivedAccountsStore`, and `DefaultWalletAccountsResponseFactory` operations.
  - Updated `DefaultWalletAccountsFetcher.pushInternal()` and `DefaultAccountsCRUDRepository.saveAccounts()` to apply account creation, renaming, reordering, and archiving/unarchiving deltas directly to local `AccountsResponseStore` and `ArchivedAccountsStore`.
  - Made `UserTokensSaver.push()` a local no-op so enabled token lists and derived public addresses are never pushed via `tangemTechApi.saveTokens()`.
  - *Trade-Off & Recovery Boundary*: Because wallet accounts and enabled token lists are never uploaded to Tangem's cloud servers, clearing app storage or switching to a new phone will reset custom account names, custom ordering, and enabled token selections to defaults until tokens/accounts are re-added locally. On-chain balances and keys on the physical cards are unaffected, as all keys and derivations remain deterministic on the card.
- **Blocked Auth Challenge Registration (`POST /v1/wallets/register`)**: Disabled `app/src/main/java/com/tangem/tap/network/auth/WalletRegistrationLauncher.kt` and set `isTangemIdentityEnabled = false` in `libs/auth/src/main/java/com/tangem/lib/auth/DefaultAuthFeatureToggles.kt`.
- **Stripped Card Identity Headers from General API & RPC Calls**: Modified `app/src/main/java/com/tangem/tap/domain/sdk/impl/DefaultTangemSdkManager.kt` and `app/src/main/java/com/tangem/tap/domain/TangemSigner.kt` so the app does not attach `cardId` and `cardPublicKey` HTTP headers to general API requests via `TangemSdkManager` or `TangemSigner`.
  - *Architectural Boundary*: Precompiled `tangem-sdk` hardware attestation (`OnlineCardVerifier` contacting `verify.tangem.com/card/verify-and-get-info` during card verification/linking), firmware/artwork checks, and specialized flows such as Visa/TangemPay activation still use `cardId` and `cardPublicKey` where required by the card protocol. All outbound requests are monitored by `HqTransparencyInterceptor`.

### Why
In upstream Tangem, activating or backing up a wallet registers `walletId` (derived from the master public key), syncs wallet accounts and enabled token lists to `api.tangem-tech.com`, and sends `POST /v1/wallets/cards` containing the `cardId` of all three physical cards/rings in a backup set. Keeping these records local prevents Tangem's backend from learning your `walletId`, enabled token list, or multi-card backup cluster topology.

---

## 3. Enforced Air-Gapped Seed Phrase Generation/Import & Offline Backup Finalization

### What Changed
- **Created `AirplaneModeGuardrail.kt`**: Added `features/onboarding-v2/impl/src/main/kotlin/com/tangem/features/onboarding/v2/multiwallet/impl/common/AirplaneModeGuardrail.kt`, which inspects Android `Settings.Global.AIRPLANE_MODE_ON` and `ConnectivityManager` (`TRANSPORT_WIFI`, `TRANSPORT_CELLULAR`, `TRANSPORT_ETHERNET`, `TRANSPORT_VPN`) in real time and displays non-dismissible system alert dialogs with a direct shortcut to Android Airplane Mode settings.
- **Seed Phrase Generation & Import Guardrail (`MultiWalletSeedPhraseModel.kt`)**: Blocks both generating a new BIP39 seed phrase in RAM (`onGenerateSeedPhraseClicked()` / `generateSeedPhrase()`) and entering or importing an existing seed phrase (`onImportClick()` / `importSeedPhrase()`) unless the phone has **Airplane Mode ON** and zero active radio transports.
- **Offline Backup Finalization Guardrail (`MultiWalletBackupModel.kt` & `MultiWalletFinalizeModel.kt`)**:
  - **Step 1 (Card Linking / Attestation)**: Prompts the user to go **Online** briefly if they are still in Airplane Mode, because the Card SDK requires `GET verify.tangem.com/card/verify-and-get-info` (`cardId` + `cardPublicKey`) to fetch Tangem's factory hardware certificate before a backup card is accepted.
  - **Step 2 (Access Code + Key Transfer / Finalize)**: The moment all 3 cards/rings are linked and their ECDH session keys are established in RAM, the app prompts the user to turn **Airplane Mode ON** before entering the Access Code or tapping the Primary Ring (`FinalizePrimaryCardTask`) and Backup Cards (`FinalizeBackupCardTask`).

### Why
When generating or importing a seed phrase (`BIP39` mnemonic + passphrase), the plaintext mnemonic and derived keys exist in phone RAM while being written to the Primary Ring/Card over NFC. During finalization, the ECDH-encrypted key bundle (`EncryptedBackupData`) passes through phone RAM as a relay between the Primary Ring and Backup Cards. Enforcing Airplane Mode before any mnemonic generation, mnemonic import, or backup finalization ensures that radio interfaces are powered down whenever key material is handled.

---

## 4. RAM-Only Backup State, Stripped Persistent Linking Keys & Elimination of Disk Logging

### What Changed
- **Replaced Disk-Backed `BackupRepo` Storage with Volatile RAM (`BackupServiceHolder.kt`, `MultiWalletFinalizeModel.kt`, `OnboardingMultiWalletModel.kt`)**:
  - In `libs/tangem-sdk-api/src/main/kotlin/com/tangem/sdk/api/BackupServiceHolder.kt`, bypassed `BackupService.init(sdk, activity)` (which uses Android `EncryptedSharedPreferences` on disk) and injected `inMemoryBackupStorage`, a volatile `ConcurrentHashMap` that returns defensive copies on read (`copyOf()`), zeroizes overwritten buffers (`put(...)?.fill(0)`), and zeroes out all stored byte arrays upon deletion or session completion (`clearActiveSession()`).
  - Retained a strong reference (`activeBackupService`) during active onboarding so the JVM garbage collector cannot evict in-progress backup state mid-onboarding, and explicitly cleared it via `clearActiveSession()` on onboarding completion, card reset, reset cancellation (`ResetCardsModelCallbacks.onCancel()`), and user back/interrupt navigation (`OnboardingMultiWalletModel.onBack()`).
  - Added startup cleanup to delete any legacy `"BackupData"` entry from `EncryptedSharedPreferences`.
- **Stripped `PrimaryCard` (`linkingKey`) from Persistent `ScanResponse` Serialization (`DefaultOnboardingRepository.kt` & `ScanResponseAsStringSerializer.kt`)**:
  - Stripped `primaryCard = null` in both `DefaultOnboardingRepository` (on save and load) and `ScanResponseAsStringSerializer` (on serialize and deserialize) so `PrimaryCard.linkingKey` is never written to persistent onboarding preferences or serialized `UserWallet.scanResponse` records on disk.
- **Disabled File, Logcat, and Chucker Disk Loggers Across All Build Variants (`DefaultAppLogsStore.kt`, `TangemCardSDKLogger.kt`, `TangemLoggingInitializer.kt`, `DefaultRetrofitFactory.kt`)**:
  - Removed `FileLogWriter`, `NetworkLogsSaveInterceptor`, and `ChuckerInterceptor` across all build variants (including Debug and Internal builds).
  - Turned `TangemCardSDKLogger.log()` and `DefaultAppLogsStore.saveLogMessage()` into no-ops, and actively delete `filesDir/log.txt` and `filesDir/log.zip` on app startup.

### Why
Bytecode inspection of `com.tangem.operations.backup.BackupRepo` in `tangem-sdk-kotlin` revealed that upstream Tangem serializes `BackupServiceData` (containing the user's `accessCode` and `EncryptedBackupData` map) to disk under `"BackupData"` in `EncryptedSharedPreferences` right after scanning the primary card, and also persists `ScanResponse.primaryCard` (`linkingKey`) in onboarding preferences. Moving `BackupRepo` to zeroized volatile RAM, stripping `primaryCard` from disk serialization, and removing all file and Chucker SQLite network loggers eliminates disk persistence of backup payloads, linking keys, and HTTP traffic.

---

## 5. Non-Intrusive HQ Network Transparency Interceptor

### What Changed
- **Created & Hardened `HqTransparencyInterceptor.kt`**: Added `core/datasource-impl/src/main/java/com/tangem/datasource/remote/interceptors/HqTransparencyInterceptor.kt` and wired it into OkHttp clients in `TangemLoggingInitializer.kt` and `DefaultRetrofitFactory.kt`:
  - Uses strict domain matching (`host.equals(domain, ignoreCase = true) || host.endsWith(".$domain", ignoreCase = true)`) rather than substring matching.
  - **Redacted Header & Query Parameter Auditing**: Detects the presence of sensitive HTTP headers (`card_id`, `card_public_key`, `Authorization`, `DPoP`) and sensitive query parameters (`cardId`, `card_id`, `cardPublicKey`, `card_public_key`, `cid`, `walletId`, `wallet_id`, `token`, `access_token`, `refresh_token`, `id_token`, `code`, `signature`, `key`, `api_key`, `apikey`, `secret`, `password`) while recording only `"[REDACTED]"` in place of their raw values so credentials and card identifiers are never retained in memory or displayed on screen.
  - **Zero Request Body Buffering**: Never reads or buffers HTTP request bodies (`body.writeTo(...)` is never called). Logs only `contentLength` and `contentType` metadata across all requests so sensitive request payloads are never copied into memory and outbound requests cannot be mutated or consumed.
  - Displays an on-screen **`📡 Outbound Request to Tangem HQ`** dialog whenever `verify.tangem.com` is contacted or any card/wallet identifier is detected, and maintains a bounded retrospective **Session Network Audit Log** strictly in volatile RAM.

### Why
`HqTransparencyInterceptor` provides a safe, bounded, retrospective viewer of outbound requests to Tangem HQ without capturing sensitive header/query values or reading request bodies.

---

## 6. Reproducible Build & Validated HTTPS Public RPC Fallbacks

### What Changed
- **Sanitized Public Config Assets & Build Fallbacks**: Added sanitized default configuration files (`config_prod.json`, `config_dev.json`, `providers_order.json`, dummy `google-services.json`) and null-safe config fallbacks across `TangemGatewayApiConfig.kt`, `Express.kt`, `Collectibles.kt`, `YieldSupply.kt`, and `TangemPay.kt`.
- **Stripped `"PLACEHOLDER"` Private RPC Credentials (`GeneratedEnvironmentConfigConverter.kt`)**: Added `.noPlaceholder()` / `.noPlaceholderOrNull()` in `core/datasource-impl/src/main/java/com/tangem/datasource/local/config/environment/converter/GeneratedEnvironmentConfigConverter.kt` when constructing `BlockchainSdkConfig` and `GetBlockCredentials`, while preserving Blockchair's keyless free tier (`apiKey = listOf("")`, `authToken = null`).
- **Added Fast HTTPS Public RPC Pool & Scoped Local Merging (`providers_order.json` & `BlockchainProvidersResponseMerger.kt`)**:
  - Added fast, non-throttled public NEAR Protocol JSON-RPC endpoints (`https://rpc.mainnet.fastnear.com/`, `https://free.rpc.fastnear.com/`, `https://near.drpc.org/`, `https://rpc.mainnet.near.org/`) and TON public JSON-RPC (`https://toncenter.com/api/v2/`) to `app/src/main/assets/tangem-app-config/providers_order.json`.
  - Updated `libs/blockchain-sdk/src/main/java/com/tangem/blockchainsdk/providers/BlockchainProvidersResponseMerger.kt` to enforce `https://` on all public provider URLs (`url.startsWith("https://", ignoreCase = true)`), filter both `local` and `remote` configurations through `filterUnsupportedProviders().filterInvalidProviders()`, prepend validated `ProviderModel.Public` local endpoints specifically for `near-protocol` (to bypass rate-limited default archival nodes), and append local public endpoints as fallbacks after remote providers for all other blockchains so remote provider priority ordering is preserved.

### Why
Upstream Tangem stores `tangem-app-config` in a private Git submodule containing proprietary API keys (NowNodes, GetBlock, QuickNode, etc.). Without those keys, `"PLACEHOLDER"` strings previously instantiated broken private RPC providers, and remote config updates overwrote local public RPC fallbacks. Filtering `"PLACEHOLDER"` credentials and preserving validated `https://` local public endpoints ensures reliable fee estimation and transaction broadcasting without proprietary API keys.

