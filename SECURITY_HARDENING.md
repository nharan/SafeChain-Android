# Security & Privacy Hardening Notes (Community Edition)

This repository is a privacy- and security-hardened fork of the official Tangem Android application. It is designed for users who want cryptographic-grade assurance that their seed phrases, access codes, encrypted backup payloads, and multi-card cluster topologies are never leaked over the network or persisted to flash storage.

---

## 1. Complete Removal of Third-Party Telemetry, Analytics & Crash Reporting

### What Changed
- **App Initialization & Analytics Router**: Stripped all analytics and crash-reporting SDK initializations and event dispatching in `app/src/main/java/com/tangem/tap/TangemApplication.kt`, `app/src/main/java/com/tangem/tap/MainActivity.kt`, and `core/analytics/src/main/java/com/tangem/core/analytics/Analytics.kt` (AppsFlyer, Amplitude, Firebase Analytics, Firebase Crashlytics, Google Analytics, Pushwoosh).
- **OpenTelemetry & A/B Testing Disabled**: Replaced `app/src/main/java/com/tangem/tap/common/analytics/handlers/opentelemetry/OpenTelemetryMetricsHolder.kt` with a no-op tracer (`TracerProvider.noop()`), disabled `OtelFeatureToggles.kt`, and bound a no-op A/B test manager in `core/abtests/src/main/kotlin/com/tangem/core/abtests/di/ABTestsManagerModule.kt`.
- **Android Manifest Lockdown**: Disabled background telemetry services, receivers, and content providers in `app/src/main/AndroidManifest.xml` (`firebase_analytics_collection_deactivated=true`, `firebase_crashlytics_collection_enabled=false`, disabled `FirebasePerfProvider`, `AppMeasurementService`, `AppMeasurementJobService`, `AppMeasurementReceiver`).

### Why
Upstream Tangem initializes multiple behavioral tracking, attribution, and telemetry pipelines on startup. Even without leaking private keys, telemetry correlates a user's IP address, device fingerprint, app usage timestamps, and onboarding steps with third-party servers.

---

## 2. Eliminating Multi-Card Topology & Wallet Registration Leaks to Tangem HQ

### What Changed
- **Blocked Multi-Card Cluster Reporting (`POST /v1/wallets/cards`)**: Replaced network calls in `data/wallets/src/main/java/com/tangem/data/wallets/DefaultWalletCardsBackupRepository.kt` and `features/onboarding-v2/impl/src/main/kotlin/com/tangem/features/onboarding/v2/multiwallet/impl/common/WalletCardsBackupReporter.kt` with local no-ops.
- **Blocked Wallet Registration & Auth Challenge (`POST /v1/wallets/register`)**: Disabled `app/src/main/java/com/tangem/tap/network/auth/WalletRegistrationLauncher.kt` and set `isTangemIdentityEnabled = false` in `libs/auth/src/main/java/com/tangem/lib/auth/DefaultAuthFeatureToggles.kt`.
- **Stripped Card Identity Headers from API & RPC Calls**: Modified `app/src/main/java/com/tangem/tap/domain/sdk/impl/DefaultTangemSdkManager.kt` and `app/src/main/java/com/tangem/tap/domain/TangemSigner.kt` so the app no longer attaches `cardId` and `cardPublicKey` HTTP headers to general API requests.

### Why
In upstream Tangem, completing a 3-card/ring backup sends `POST /v1/wallets/cards` to Tangem HQ containing a `walletId` (derived from the master public key) along with the `cardId` of all three physical cards/rings in the set, permanently linking those three hardware serial numbers together on Tangem's backend. Furthermore, `WalletRegistrationLauncher` registers the wallet and signs an authentication challenge with the card. Stripping these ensures Tangem HQ never learns your `walletId`, never knows which cards belong to the same backup cluster, and cannot track which `cardId` is making price/balance requests.

---

## 3. Enforced Air-Gapped Seed Phrase Import & Offline Backup Finalization

### What Changed
- **Created `AirplaneModeGuardrail.kt`**: Added `features/onboarding-v2/impl/src/main/kotlin/com/tangem/features/onboarding/v2/multiwallet/impl/common/AirplaneModeGuardrail.kt`, which inspects Android `Settings.Global.AIRPLANE_MODE_ON` and `ConnectivityManager` (`TRANSPORT_WIFI`, `TRANSPORT_CELLULAR`, `TRANSPORT_ETHERNET`, `TRANSPORT_VPN`) in real time and displays non-dismissible system alert dialogs with a direct shortcut to Android Airplane Mode settings.
- **Seed Phrase Entry Guardrail (`MultiWalletSeedPhraseModel.kt`)**: Blocks entering the **"Import wallet"** screen and blocks `importSeedPhrase()` (`createProductWallet`) unless the phone has **Airplane Mode ON** and zero active radio transports.
- **Offline Backup Finalization Guardrail (`MultiWalletBackupModel.kt` & `MultiWalletFinalizeModel.kt`)**:
  - **Step 1 (Card Linking / Attestation)**: Prompts the user to go **Online** briefly if they are still in Airplane Mode, because the Card SDK requires `GET verify.tangem.com/card/verify-and-get-info` (`cardId` + `cardPublicKey`) to fetch Tangem's factory hardware certificate before a backup card is accepted.
  - **Step 2 (Access Code + Key Transfer / Finalize)**: The moment all 3 cards/rings are linked and their ECDH session keys are established in RAM, the app prompts the user to turn **Airplane Mode ON** before entering the Access Code or tapping the Primary Ring (`FinalizePrimaryCardTask`) and Backup Cards (`FinalizeBackupCardTask`).

### Why
When importing a seed phrase (`BIP39` mnemonic + passphrase), the plaintext private keys exist in phone RAM while being written to the Primary Ring/Card over NFC. During finalization, the ECDH-encrypted key bundle (`EncryptedBackupData`) passes through phone RAM as a relay between the Primary Ring and Backup Cards. Enforcing Airplane Mode guarantees that all radio interfaces are physically powered down whenever key material (plaintext or encrypted) is handled.

---

## 4. Zero Disk Persistence (RAM-Only Backup State & Log Elimination)

### What Changed
- **Replaced Disk-Backed `BackupRepo` Storage with Volatile RAM (`BackupServiceHolder.kt`)**:
  - In `libs/tangem-sdk-api/src/main/kotlin/com/tangem/sdk/api/BackupServiceHolder.kt`, bypassed `BackupService.init(sdk, activity)` (which uses Android `EncryptedSharedPreferences` on disk) and injected `inMemoryBackupStorage`, a volatile `ConcurrentHashMap` that zeroes out byte arrays (`bytes.fill(0)`) upon deletion.
  - Added startup cleanup to explicitly delete any legacy `"BackupData"` entry from `EncryptedSharedPreferences`.
- **Disabled All Disk Log Writers (`DefaultAppLogsStore.kt`, `TangemCardSDKLogger.kt`, `TangemLoggingInitializer.kt`)**:
  - Removed `FileLogWriter` and `NetworkLogsSaveInterceptor`.
  - Turned `TangemCardSDKLogger.log()` and `DefaultAppLogsStore.saveLogMessage()` into no-ops, and actively delete `filesDir/log.txt` and `filesDir/log.zip` on app startup.

### Why
Bytecode inspection of `com.tangem.operations.backup.BackupRepo` in `tangem-sdk-kotlin` revealed that upstream Tangem temporarily serializes `BackupServiceData` (containing the user's `accessCode` and `EncryptedBackupData` map) to disk under `"BackupData"` in `EncryptedSharedPreferences` right after scanning the primary card so interrupted backups can resume after an app crash. Upstream also logs NFC APDU/TLV events and network traffic to `filesDir/log.txt`. Moving `BackupRepo` to zeroed volatile RAM and disabling disk logging ensures **zero flash wear and zero forensic remnants** on the device's storage.

---

## 5. Real-Time HQ Network Transparency Interceptor

### What Changed
- **Created `HqTransparencyInterceptor.kt`**: Added `core/datasource-impl/src/main/java/com/tangem/datasource/remote/interceptors/HqTransparencyInterceptor.kt` and wired it into all OkHttp clients in `TangemLoggingInitializer.kt` and `DefaultRetrofitFactory.kt`:
  - Displays an immediate on-screen **`📡 Data Sent to Tangem HQ`** alert dialog whenever the Card SDK contacts `verify.tangem.com` (or whenever any request includes `cardId` / `cardPublicKey`), listing the exact HTTP method, URL, every query parameter, request body byte count, and response code.
  - Displays a non-intrusive toast for public `api.tangem-tech.com` queries and maintains a scrollable **Session Network Audit Log** strictly in volatile RAM.

### Why
Instead of asking users to trust that only `cardId` and `cardPublicKey` are sent during hardware attestation, the app provides immediate, real-time visibility into every outbound packet sent to Tangem HQ without requiring an external packet sniffer or proxy.

---

## 6. Reproducible Open-Source Build & Public RPC Reliability Without Proprietary Secrets

### What Changed
- **Sanitized Public Config Assets & Build Fallbacks**: Added sanitized default configuration files (`config_prod.json`, `config_dev.json`, `providers_order.json`, dummy `google-services.json`) and null-safe config fallbacks across `TangemGatewayApiConfig.kt`, `Express.kt`, `Collectibles.kt`, `YieldSupply.kt`, and `TangemPay.kt`.
- **Stripped `"PLACEHOLDER"` Private RPC Credentials (`GeneratedEnvironmentConfigConverter.kt`)**: Added `.noPlaceholder()` / `.noPlaceholderOrNull()` in `core/datasource-impl/src/main/java/com/tangem/datasource/local/config/environment/converter/GeneratedEnvironmentConfigConverter.kt` when constructing `BlockchainSdkConfig` and `GetBlockCredentials`, while preserving Blockchair's keyless free tier (`apiKey = listOf("")`, `authToken = null`).
- **Added Fast Public RPC Pool & Local-First Provider Merging (`providers_order.json` & `BlockchainProvidersResponseMerger.kt`)**:
  - Added fast, non-throttled public NEAR Protocol JSON-RPC endpoints (`https://rpc.mainnet.fastnear.com/`, `https://free.rpc.fastnear.com/`, `https://near.drpc.org/`, `https://rpc.mainnet.near.org/`) and TON public JSON-RPC (`https://toncenter.com/api/v2/`) to `app/src/main/assets/tangem-app-config/providers_order.json`.
  - Updated `libs/blockchain-sdk/src/main/java/com/tangem/blockchainsdk/providers/BlockchainProvidersResponseMerger.kt` so that merging remote provider order from `api.tangem-tech.com/v1/config/providers` preserves local public fallback endpoints (`(localProviders + providers).distinct()`) instead of overwriting them.

### Why
Upstream Tangem stores `tangem-app-config` in a private Git submodule containing proprietary API keys (NowNodes, GetBlock, QuickNode, etc.). In an open-source build where those keys are `"PLACEHOLDER"`, `blockchain-sdk` treats `"PLACEHOLDER"` as a non-empty string (`StringUtilsKt.notnullOrEmpty("PLACEHOLDER") == true`) and instantiates broken private RPC providers (such as `https://go.getblock.io/PLACEHOLDER` and `https://near.nownodes.io` with `api-key: PLACEHOLDER`). Furthermore, `api.tangem-tech.com/v1/config/providers` overrides local `providers_order.json` on startup, leaving NEAR Protocol with two dead private endpoints and one heavily throttled archival endpoint (`archival-rpc.mainnet.near.org`, ~3 seconds per call, or ~9+ seconds for the 3-call `getFee` sequence), causing intermittent `"Network fee info unreachable"` errors. Mapping `"PLACEHOLDER"` to `""`/`null` skips unconfigured private providers immediately, and preserving local public RPCs ensures fast, reliable fee estimation and transaction broadcasting without proprietary API keys.
