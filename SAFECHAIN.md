# SafeChain-Android

SafeChain's mobile wallet. It's a fork of the **hardened** Tangem app (`github.com/nharan/tangem-app-android`,
see `HANDOFF_SAFECHAIN.md` and `SECURITY_AUDIT_2026-10-23.md`), which is itself a fork of
`github.com/tangem/tangem-app-android`. The protocol, contracts, web wallet, watcher and their security
reviews live in `github.com/nharan/SafeChain`.

**Test network only (Sepolia), valueless test tokens. Not audited.**

## Remotes

| Remote | Repository | Use |
|---|---|---|
| `origin` | `nharan/SafeChain-Android` (private) | this app |
| `upstream` | `nharan/tangem-app-android` | the hardening fixes: merge `upstream/master` regularly |
| `tangem` | `tangem/tangem-app-android` | Tangem's original, for reference |

SafeChain code lives in its own modules (`features/safechain/{api,impl}`), so hardening merges rarely conflict.
Outside them it touches:
- `settings.gradle.kts`
- `app/build.gradle.kts`
- `AppRoute.SafeChain`
- one branch in `ChildFactory`
- one item in Settings (`features/details/.../ItemsBuilder.kt`)

## What it does

**Settings → "SafeChain Protected Savings (Sepolia)"** opens the SafeChain screen for the current card wallet:
- **Your card is the spending key.** It uses the same Ethereum key and address as the Tangem app's Ethereum
  receive screen (`m/44'/60'/0'/0/0`, or the seed key on non-HD cards). The screen shows the address so you
  can compare.
- **Create Protected Savings.** Enter the recovery key's address (on another device) and the protection
  period. The app shows a consent link for the SafeChain web wallet (Sepolia mode). Sign it there with the
  recovery key, paste the signature back, and tap the card to create the vault. It starts with no safe
  haven; add one later with both keys.
- **Savings:**
  - balance
  - pending withdrawals with countdowns and **Cancel**
  - **Request withdrawal** (amount, destination)
  - **Lock Savings**
  - **Move everything to safe haven** (when a haven is set)
  - **Add funds** (approve + deposit, two taps)
  - **Release** of final withdrawals
  - warnings for a pending withdrawal, a scheduled key replacement, or a rescue window
- **Every action is checked before you tap:**
  1. The call is simulated on the RPC endpoints, so the contract's own checks run first.
  2. A confirmation shows exactly what will be signed, with full addresses, the contract, the network and
     the maximum fee. The card has no screen, so this is where you check.
  3. Tap the card.
  4. The transaction is broadcast to both endpoints, and the receipt is checked to come from the card's
     address.
- **Reads are cross-checked** across two independent public endpoints (publicnode, Tenderly), pinned to the
  same block, with block hashes compared.
  - **Disagreement:** a red banner says to verify on an independent explorer, and disputed items show their
    most urgent reading (still cancellable, earliest deadline, largest amount, never a haven move,
    unlocked). These are the web wallet's rules.
  - **An endpoint down, behind or failing:** a yellow banner.

## Code (`features/safechain/impl`)

| File | What |
|---|---|
| `chain/Abi.kt` | ABI encoding of the SafeChain calls |
| `chain/Eip1559Tx.kt` | RLP and type-2 transactions (Sepolia, chain 11155111) |
| `chain/EvmSignature.kt` | card r‖s → canonical low-S + recovery id (the SDK's `UnmarshalHelper`) |
| `chain/JsonRpc.kt` | plain OkHttp JSON-RPC. The hardened `TangemRequestPolicy` only governs Tangem hosts; no Tangem host is contacted here |
| `chain/CrossCheck.kt` | two-endpoint read with block-hash check, and the urgent merge |
| `chain/VaultReader.kt` | finds your vaults (indexed events, factory-registry check) and reads their state |
| `chain/TxSender.kt` | simulate, build, broadcast, wait, check sender |
| `signing/CardEthSigner.kt` | the card's Ethereum key and signing (same path as Polymarket's signer in this app) |
| `model/`, `ui/` | the screen |

The deployment addresses are in `chain/SepoliaConfig.kt`, from `SafeChain/contracts/deployments/sepolia.json`.

## Tests

```bash
./gradlew :features:safechain:impl:testDebugUnitTest --offline "-PversionName=6.4.1"
```

- **Signing core.** Calldata, transaction encoding, signing hash and the signed transaction match **viem**,
  the library the SafeChain web wallet uses, byte for byte. The vectors come from
  `SafeChain/web/scripts/gen-android-vectors.mjs`. This includes a high-S card signature being
  canonicalised.
- **Cross-check:** agree, different chains, endpoint down, stale endpoint, a hidden theft shown as disputed,
  and the urgent merge rules.
- **Live, opt-in:** with `SAFECHAIN_LIVE=1`, it reads the card's real Sepolia account through both endpoints
  (read-only).

## Building

The build uses JDK 17 (`C:\Developer\tools\jdk17`) and the Android SDK (`C:\Developer\tools\android-sdk`).
Tangem's SDK packages come from the local Gradle cache, hence `--offline`, so no GitHub token is needed
here:

```bash
./gradlew :app:assembleGoogleExternal --offline "-PversionName=6.4.1" -x :core:ui:verifyDesignTokens
```

## Not done or not verified yet

- **Not yet run on a phone with the real card.** Signing is verified against reference vectors, but the NFC
  path (`CardSdkConfigRepository.getCommonSigner`, the same one Polymarket uses) hasn't been exercised for
  SafeChain.
- No protected-payment (escrow) screens yet, and no safe-haven setup or key rotation from the phone.
- No independent alerts on the phone. Use the SafeChain watcher on another device.
- The prebuilt Tangem SDK binaries are still the fork's main trust gap (see `HANDOFF_SAFECHAIN.md`).
