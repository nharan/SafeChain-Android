# Handoff: hardened Tangem Android fork → SafeChain

For the SafeChain session (`C:\Developer\SafeChain`). That project's `audits/tangem-android/` reviewed **stock**
Tangem `v6.4.1`. This file describes the **hardened fork** at `C:\Developer\tangem-app-android`
(`github.com/nharan/tangem-app-android`, branch `master`), which is the candidate mobile signer for the vault.

Full detail: [`SECURITY_AUDIT_2026-10-23.md`](SECURITY_AUDIT_2026-10-23.md) (status table at the top, plus a
"What is still disclosed" table) and [`SECURITY_HARDENING.md`](SECURITY_HARDENING.md).

## Commits

From stock `19a2e30bdee`, in order: `cbf3a7772a6` `c0c6586808b` `b5332f047c1` `65219ceddab` `88146798b59`
`f9cd0f14dd1` (first hardening pass, audit notes F-01–F-20), then `260bebcdb1c` `bca09a913ce` `b5bf8304edd`
`fb5f6ca3f2b` and the commit that adds this file (four rounds of external review, findings K-* and N-*).

## What the fork guarantees, as implemented (not independently verified)

**Keys and seeds**
- Card keys are generated and used on the card; signing code is unchanged from upstream.
- Multi-card backup state (access code, encrypted key bundle) is held in RAM only, never in
  `EncryptedSharedPreferences` (`libs/tangem-sdk-api/.../BackupServiceHolder.kt`).
- Hot-wallet Google Drive seed backup is off, *and* refused in `DefaultCloudBackupRepository.uploadBackup` (upload
  code removed); the backup password is never saved to the password manager.
- All app data is excluded from Android cloud backup and device-to-device transfer.
- Seed generation/import and backup finalization require airplane mode; the check fails closed when the network
  state can't be read. Tap-time only, and not applied to the hot-wallet screens.

**Card authenticity**
- Card SDK runs `AttestationTask.Mode.Normal`: the card certificate is verified against the manufacturer key embedded
  in the SDK. `Mode.Offline` was tried and reverted — it accepts counterfeit cards.
- A card is accepted only with status `Verified` (`ScanProductTask`); the SDK's "continue anyway" paths are rejected.
- Wallet creation / seed import re-attests in the same NFC session and requires the same card public key as the scan
  (`CreateProductWalletTask` → `VerifyScannedCardTask`). Backup finalization and transaction signing do **not**
  re-attest this way.
- The signed verification is cached per card public key: Tangem's verification server sees `cardId` + card public
  key **once per card per install** (first scan needs network once).
- Attestation proves the card key chains to Tangem's manufacturer key. It says nothing about which firmware runs.

**Network**
- No analytics, crash reporting, attribution, push registration, wallet/card registration, or account/token sync.
- `TangemRequestPolicy` (in `core/datasource-impl/.../interceptors/`) is default-deny for Tangem hosts on every
  `DefaultRetrofitFactory` client: public price/market GETs on `api.tangem.org`, Hedera account creation, and the
  reviewed endpoints of `express.tangem.com`, `yield.tangem.org`, `gasless.tangem.org`. Everything else is refused,
  including any walletId query parameter. Polymarket is refused (its backend keys users by raw walletId).
- Wallet-identifier headers are replaced with HMAC-SHA256(on-device secret, host ‖ walletId) — a different stable
  pseudonym per service.
- Redirects to a different origin are refused on every hop (real two-server test: `RedirectOriginBindingTest`).
- Card SDK traffic (attestation, artwork) bypasses this policy; it is not built through `DefaultRetrofitFactory`.
- Still disclosed: IP everywhere; the token list per wallet pseudonym to Express on balance refresh; addresses and
  transaction hashes to Express/Onramp/Yield/Gasless when the user uses them; every tracked address to blockchain RPC
  providers; staking balances to StakeKit/P2P.

## What matters for SafeChain specifically

- **Prebuilt SDKs (biggest open trust gap).** The Card SDK (`com.tangem.tangem-sdk-kotlin` `releases-6.4-672`),
  Blockchain SDK (`releases-6.4-1707`) and Hot SDK still come as Tangem-built binaries from GitHub Packages
  (`gradle/tangem_dependencies.toml`). SafeChain's own audit recommends building them from audited commits into
  `mavenLocal`; that has **not** been done here. No Gradle dependency verification either.
- **Recovery key independence.** Ring + backup cards share one key, as SafeChain's spec §13.1 notes. The fork changes
  nothing there; the vault's recovery key must come from somewhere else.
- **EVM signing path.** Transactions are signed through `TangemSigner` (`app/.../tap/domain/TangemSigner.kt`) via
  the Blockchain SDK; WalletConnect `eth_sign`/typed-data exist (`data/wallet-connect`). There is no vault-aware flow
  yet: requesting/cancelling withdrawals or locking a `SavingsVault` would be new screens + contract calls, or done
  from SafeChain's web wallet with the phone as an NFC signer (SafeChain's `integrations/tangem/BRIDGE.md` mock).
- **Airplane mode vs first scan.** A card's first scan needs network once (manufacturer verification). Plan
  onboarding so that happens before going offline for seed/backup steps.

## Open items in the fork

- Not tested on a device or with a real card: the attestation gate, the re-attest before import, the backup flow
  with RAM-only state, and a network capture while scanning existing cards.
- Open findings (Medium/Low): per-screen `FLAG_SECURE` gaps (K-3/K-4), seed-field clipboard/keyboard hygiene,
  mnemonic held as `String` in UI state (K-6–K-8), device/timezone headers (N-6), Crashlytics/Perf still linked
  (N-7), `wss://` RPC providers rejected (R-1). See the audit status table.
- Pre-existing unit test failures unrelated to the hardening: `DefaultAppDataStoreFactoryTest` (2 tests), and config
  tests that still expect the old placeholder base URLs.

## Building

- JDK 17 at `C:\Developer\tools\jdk17` (set `JAVA_HOME`; none on `PATH`).
- `./gradlew :app:assembleGoogleExternal` builds; `local.properties` needs `gpr.user`/`gpr.key` for the Tangem
  packages.
- Android library modules test with `:<module>:testDebugUnitTest`, the app with `:app:testGoogleDebugUnitTest`.
