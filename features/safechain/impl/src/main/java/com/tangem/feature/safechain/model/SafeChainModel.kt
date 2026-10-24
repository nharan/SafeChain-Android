package com.tangem.feature.safechain.model

import android.content.Context
import android.content.pm.ApplicationInfo
import arrow.core.getOrElse
import com.tangem.blockchain.common.Wallet
import com.tangem.common.routing.AppRoute
import com.tangem.core.decompose.di.ModelScoped
import com.tangem.core.decompose.model.Model
import com.tangem.core.decompose.model.ParamsContainer
import com.tangem.core.decompose.navigation.Router
import com.tangem.feature.safechain.chain.AccountSnapshot
import com.tangem.feature.safechain.chain.Eip1559Tx
import com.tangem.feature.safechain.chain.ActivityView
import com.tangem.feature.safechain.chain.CrossCheckedReader
import com.tangem.feature.safechain.chain.Eip712
import com.tangem.feature.safechain.chain.EthRpc
import com.tangem.feature.safechain.chain.Hex
import com.tangem.feature.safechain.chain.OkHttpEthRpc
import com.tangem.feature.safechain.chain.PaymentView
import com.tangem.feature.safechain.chain.RpcHealth
import com.tangem.feature.safechain.chain.SafeChainCalls
import com.tangem.feature.safechain.chain.SepoliaConfig
import com.tangem.feature.safechain.chain.Token
import com.tangem.feature.safechain.chain.TxSender
import com.tangem.feature.safechain.chain.NotMinedYet
import com.tangem.feature.safechain.chain.ScannedAddress
import com.tangem.feature.safechain.chain.lookAlike
import com.tangem.feature.safechain.chain.parseUserAddress
import com.tangem.feature.safechain.chain.scannedAddress
import com.tangem.feature.safechain.chain.of
import com.tangem.feature.safechain.chain.VaultReader
import com.tangem.feature.safechain.chain.VaultView
import com.tangem.feature.safechain.chain.WithdrawalView
import com.tangem.feature.safechain.chain.ZERO_ADDRESS
import com.tangem.feature.safechain.chain.checksumAddress
import com.tangem.feature.safechain.chain.groupedAddress
import com.tangem.feature.safechain.signing.CardEthSigner
import com.tangem.feature.safechain.signing.EthAccount
import com.tangem.feature.safechain.signing.PhoneKeyError
import com.tangem.feature.safechain.signing.PhoneKeyStore
import com.tangem.feature.safechain.signing.SafeChainSignerError
import com.tangem.feature.safechain.signing.toEip712Bytes
import com.tangem.feature.safechain.ui.ActivityUM
import com.tangem.feature.safechain.ui.AssetUM
import com.tangem.feature.safechain.ui.BalanceUM
import com.tangem.feature.safechain.ui.BackupChangeUM
import com.tangem.feature.safechain.ui.HealthUM
import com.tangem.feature.safechain.ui.HoldUnit
import com.tangem.feature.safechain.ui.MAX_HOLD_SECONDS
import com.tangem.feature.safechain.ui.MIN_HOLD_SECONDS
import com.tangem.feature.safechain.ui.holdSeconds
import com.tangem.feature.safechain.ui.holdText
import com.tangem.feature.safechain.ui.PreviousVaultUM
import com.tangem.feature.safechain.ui.SafeChainActions
import com.tangem.feature.safechain.ui.SafeChainUM
import com.tangem.feature.safechain.ui.SetupUM
import com.tangem.feature.safechain.ui.SheetUM
import com.tangem.feature.safechain.ui.Source
import com.tangem.feature.safechain.ui.VaultUM
import com.tangem.feature.safechain.ui.WithdrawalUM
import com.tangem.features.safechain.SafeChainComponent
import com.tangem.domain.qrscanning.models.SourceType
import com.tangem.domain.qrscanning.usecases.ListenToQrScanningUseCase
import com.tangem.utils.coroutines.CoroutineDispatcherProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.security.SecureRandom
import java.text.DateFormat
import java.util.Base64
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * SafeChain Protected Savings on Sepolia: the Tangem card is the spending key, this phone is the backup key
 * (docs/plan-phone-recovery.md in SafeChain). Every signing action is one guided flow in a bottom sheet:
 * simulate on the endpoints (the contract's own checks run first) → Review, with full addresses → card tap or
 * fingerprint → "Sending to Sepolia" → Done (or the error, in the same sheet). Reads are cross-checked across
 * two independent endpoints, block hash included.
 */
@ModelScoped
@Suppress("LargeClass", "TooManyFunctions")
internal class SafeChainModel @Inject constructor(
    override val dispatchers: CoroutineDispatcherProvider,
    paramsContainer: ParamsContainer,
    private val router: Router,
    private val signer: CardEthSigner,
    private val phoneKeys: PhoneKeyStore,
    private val listenToQrScanning: ListenToQrScanningUseCase,
    @ApplicationContext private val context: Context,
) : Model(), SafeChainActions {

    /** Which field the next QR scan fills. */
    private enum class ScanTarget { Recovery, Destination, Signature, OtherWallet, OtherSignature }
    private var scanTarget: ScanTarget? = null

    /** Who signs: the card (one tap, possibly several transactions) or this phone's backup key (fingerprint). */
    private enum class By { Card, Phone }

    private data class Call(val to: String, val data: ByteArray, val value: BigInteger = BigInteger.ZERO)

    private val params: SafeChainComponent.Params = paramsContainer.require()

    private val http = OkHttpClient.Builder()
        .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
        .followRedirects(false)
        .build()
    private val endpoints: List<EthRpc> = SepoliaConfig.RPC_URLS.map { OkHttpEthRpc(it, http) }
    private val reader = CrossCheckedReader(endpoints)
    private val vaultReader = VaultReader()
    private val sender = TxSender(endpoints)

    private var account: EthAccount? = null
    private var phoneKey: String? = null
    private var phoneKeyWei = BigInteger.ZERO
    private var snapshot: AccountSnapshot? = null
    private var snapshotBlock = BigInteger.ZERO
    private var snapshotReadAtMs = 0L
    private var health: RpcHealth? = null

    /** Readings in a row where one data source failed; one blip (a rate limit) isn't worth a banner. */
    private var degradedReads = 0

    /** Whether any reading this session was cross-checked. Until one is, a single-source reading is flagged at once. */
    private var agreedOnce = false
    private var salt: ByteArray? = null
    private var busy = false

    /**
     * Debug builds only, and only for a wallet that isn't a Tangem card (an emulator has no NFC): a pretend
     * Sepolia ([DemoChain]) so every screen can be tried. A release or external build never enters it.
     */
    private var demo: DemoChain? = null

    // Declared before init {}: its first read uses them, and properties initialise in declaration order.
    /** On-phone memory: vaults confirmed as the user's own, and addresses this app has paid (per card). */
    private val prefs by lazy { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    private val demoTrusted = mutableSetOf<String>()
    private val demoRecipients = mutableSetOf<String>()

    /** The prepared transactions behind the Review sheet, and what comes after they're mined. */
    private var pending: Pending? = null

    /** [recipient]: an outside address being paid, remembered once sent (for the look-alike check). */
    private data class Pending(
        val txs: List<TxSender.Prepared>,
        val by: By,
        val from: String,
        val doneTitle: String,
        val then: (suspend () -> Unit)?,
        val recipient: String? = null,
    )

    val state: StateFlow<SafeChainUM>
        field = MutableStateFlow(SafeChainUM())

    init {
        modelScope.launch {
            account = runCatching { signer.account(params.userWalletId) }
                .recoverCatching { e -> if (e is SafeChainSignerError.NotACard && isDebuggable()) startDemo() else throw e }
                .onFailure { e -> state.update { it.copy(loading = false, loadError = e.message) } }
                .getOrNull() ?: return@launch
            phoneKey = if (demo != null) DemoChain.PHONE else phoneKeys.address(account!!.address)
            state.update { it.copy(accountAddress = account!!.address, phoneKey = phoneKey, demo = demo != null) }
            refresh()
        }
        listenToQrScanning(SourceType.SAFECHAIN).getOrElse { emptyFlow() }
            .onEach(::onScanned)
            .launchIn(modelScope)
        modelScope.launch { // re-read regularly, and keep the countdowns ticking every second
            while (isActive) {
                delay(TICK_MS)
                if (!busy && System.currentTimeMillis() - snapshotReadAtMs > REFRESH_MS) refresh()
                render()
            }
        }
    }

    private fun isDebuggable() = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    private fun startDemo(): EthAccount {
        demo = DemoChain()
        // A placeholder key: in the demo nothing is ever signed.
        return EthAccount(DemoChain.CARD, Wallet.PublicKey(seedKey = ByteArray(DEMO_KEY_BYTES), derivationType = null))
    }

    // ----------------------------------------------------------------- reading

    private suspend fun refresh() {
        val acct = account ?: return
        demo?.let { d ->
            snapshot = d.snapshot(acct.address)
            snapshotBlock = d.head
            health = RpcHealth.Agree
            agreedOnce = true
            rememberTrusted(ownVaults(snapshot).map { it.address })
            snapshotReadAtMs = System.currentTimeMillis()
            phoneKeyWei = d.ethBalance(DemoChain.PHONE)
            state.update { it.copy(loading = false, loadError = null) }
            render()
            return
        }
        try {
            val checked = reader.read(AccountSnapshot::mergeUrgent) { rpc, block -> vaultReader.snapshot(rpc, block, acct.address) }
            snapshot = checked.value
            snapshotBlock = checked.block.number
            health = checked.health
            degradedReads = if (checked.health is RpcHealth.Degraded) degradedReads + 1 else 0
            if (checked.health is RpcHealth.Agree) {
                agreedOnce = true
                // Remembered only when both endpoints agree: a single lying endpoint can't plant a "vault".
                rememberTrusted(ownVaults(checked.value).map { it.address })
            }
            snapshotReadAtMs = System.currentTimeMillis()
            phoneKey?.let { key ->
                phoneKeyWei = endpoints.firstNotNullOfOrNull { runCatching { it.balance(key, checked.block.number) }.getOrNull() } ?: phoneKeyWei
            }
            state.update { it.copy(loading = false, loadError = null) }
            render()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.update { it.copy(loading = false, loadError = "Couldn't reach Sepolia: ${e.message}") }
        }
    }

    private fun chainNow(): Long {
        val s = snapshot ?: return 0
        return s.chainTime + (System.currentTimeMillis() - snapshotReadAtMs) / 1000
    }

    /**
     * The vaults that are the user's own: created by this card (the factory's VaultCreated names it as primary,
     * which only the card itself can cause) and reported so by every endpoint. A vault that reached the card
     * through a key rotation never qualifies: replacePrimary needs no consent from the new key, so anyone can
     * hand the card a vault they control (security review 2026-10-24, Critical).
     */
    private fun ownVaults(s: AccountSnapshot? = snapshot): List<VaultView> = s?.vaults.orEmpty().filter { it.createdByMe && !it.disputed }

    /**
     * The vault the screen is about: the newest own vault this phone has seen confirmed by agreeing endpoints
     * (remembered on the phone). Before any is remembered (first run), the newest own vault.
     */
    private fun current(s: AccountSnapshot? = snapshot): VaultView? {
        val own = ownVaults(s)
        val trusted = trustedVaults()
        return own.lastOrNull { it.address.lowercase() in trusted } ?: own.lastOrNull().takeIf { trusted.isEmpty() }
    }

    private fun prefsKey(kind: String) = "$kind.${account?.address?.lowercase()}"

    /** Own vaults confirmed by agreeing endpoints: the only ones money is moved into. */
    private fun trustedVaults(): Set<String> = if (demo != null) demoTrusted else prefs.getStringSet(prefsKey("trusted"), emptySet()).orEmpty()

    private fun rememberTrusted(addresses: Collection<String>) {
        val merged = trustedVaults() + addresses.map { it.lowercase() }
        if (demo != null) demoTrusted += merged else prefs.edit().putStringSet(prefsKey("trusted"), merged).apply()
    }

    /** Addresses this app has signed payments to: what the look-alike check compares against. */
    private fun recipients(): Set<String> = if (demo != null) demoRecipients else prefs.getStringSet(prefsKey("paid"), emptySet()).orEmpty()

    private fun rememberRecipient(address: String) {
        val merged = recipients() + address.lowercase()
        if (demo != null) demoRecipients += merged else prefs.edit().putStringSet(prefsKey("paid"), merged).apply()
    }

    /** A deposit into Savings goes only to a vault confirmed by agreeing endpoints. */
    private fun depositTarget(): VaultView? = current()?.takeIf { it.address.lowercase() in trustedVaults() }

    /** Moving money while the endpoints disagree could follow a lying endpoint's story; protective actions stay. */
    private fun blockedByDisagreement(): Boolean {
        if (health !is RpcHealth.Disagree) return false
        sheet(
            SheetUM.Result(
                ok = false,
                title = "Not now",
                text = "Your two Sepolia data sources disagree, so this can't be checked. Cancelling and locking still work. Try again in a moment, or check safechain.rep.run.",
                url = null,
            ),
        )
        return true
    }

    private fun render() {
        val s = snapshot ?: return
        val me = account?.address ?: return
        val now = chainNow()
        val cur = current(s)
        val vaultUM = cur?.toUM(me, now, s)
        // Upcoming: both accounts' payments on their way (and protected payments coming in), soonest first.
        val savingsUpcoming = cur?.let { v ->
            v.withdrawals.filter { it.state in 1..2 }.map { it.deadline to it.toUM(me, now, v.delaySeconds, s, v.address) }
        }.orEmpty()
        val checkingUpcoming = s.payments.filter { it.state in 1..2 }.map { it.deadline to it.toUM(me, now, s) }
        val upcoming = (savingsUpcoming + checkingUpcoming).sortedBy { it.first }.map { it.second }
        // Activity: Savings' and Checking's history together, newest first.
        val activity = (cur?.let { v -> v.activity.map { it to v.address } }.orEmpty() + s.checkingActivity.map { it to "" })
            .sortedWith(compareByDescending<Pair<ActivityView, String>> { it.first.block }.thenByDescending { it.first.logIndex })
            .take(MAX_ACTIVITY_ROWS)
            .map { (a, vault) -> a.toUM(me, s, vault) }
        state.update { ui ->
            ui.copy(
                upcoming = upcoming,
                activity = activity,
                total = balance(COINS.map { it to checkingOf(s, it) + (cur?.balances?.of(it.address) ?: BigInteger.ZERO) }),
                checking = balance(COINS.map { it to checkingOf(s, it) }),
                assets = assets(s, cur),
                gasBalance = "${fmt(s.ethBalance)} Sepolia ETH",
                phoneKey = phoneKey,
                phoneKeyGas = "${fmt(phoneKeyWei)} Sepolia ETH",
                phoneKeyLow = phoneKey != null && phoneKeyWei < PHONE_GAS_LOW,
                health = when (val h = health) {
                    RpcHealth.Agree, null -> HealthUM.Agree
                    // Disagreement always shows at once; a source that's down shows once it stays down.
                    // Until something was cross-checked this session, it shows at once.
                    is RpcHealth.Degraded ->
                        if (degradedReads >= DEGRADED_READS_TO_WARN || !agreedOnce) HealthUM.Degraded(h.problems.joinToString(" · ")) else HealthUM.Agree
                    is RpcHealth.Disagree -> HealthUM.Disagree(h.detail)
                },
                vault = vaultUM?.let { v -> v.copy(alerts = v.alerts + foreignVaultAlerts(s, me) + hiddenWithdrawalAlert(cur)) },
                previous = ownVaults(s)
                    .filter { it !== cur && it.primary.equals(me, ignoreCase = true) }
                    .filter { it.holdsAnything || it.withdrawals.any { w -> w.state in 1..2 } }
                    .map { v ->
                        PreviousVaultUM(
                            address = v.address,
                            balance = moneyList(v.balances),
                            available = moneyList(v.available),
                            canMove = cur != null && !v.locked && v.available.values.any { it.signum() > 0 },
                            moving = v.withdrawals.filter { it.state in 1..2 }.map { it.toUM(me, now, v.delaySeconds, s, v.address) },
                        )
                    },
            )
        }
    }

    /** A destination that is one of SafeChain's own contracts: tokens sent there are lost for good. */
    private fun contractDestination(to: String): String? {
        val t = to.lowercase()
        return when {
            SepoliaConfig.token(to) != null -> "That's the ${SepoliaConfig.token(to)!!.symbol} token contract, not a person's address. Tokens sent there are lost."
            t == SepoliaConfig.ESCROW.lowercase() || t == SepoliaConfig.FACTORY.lowercase() -> "That's a SafeChain contract, not a person's address."
            snapshot?.vaults.orEmpty().any { it.address.equals(to, ignoreCase = true) } -> "That's a Savings vault address. Use Move to put money in Savings."
            t == ZERO_ADDRESS -> "That's the zero address: anything sent there is gone."
            else -> null
        }
    }

    /** Vaults this card didn't open but which name it as spending key: shown, never used. */
    private fun foreignVaultAlerts(s: AccountSnapshot, me: String): List<String> = s.vaults
        .filter { !it.createdByMe && it.primary.equals(me, ignoreCase = true) }
        .map {
            "A Savings you didn't open (${short(it.address)}) names this card as its spending key. Anyone can do that. " +
                "This app ignores it: never move money into it, and don't trust messages about it."
        }

    /**
     * Money reserved in Savings that no listed withdrawal accounts for: a pending withdrawal this app can't show
     * (for example buried under many others). The contract's own numbers say it's there.
     */
    private fun hiddenWithdrawalAlert(v: VaultView?): List<String> {
        v ?: return emptyList()
        val hidden = SepoliaConfig.TOKENS.any { t ->
            val reserved = (v.balances.of(t.address) - v.available.of(t.address)).max(BigInteger.ZERO)
            val shown = v.withdrawals
                .filter { it.token.equals(t.address, ignoreCase = true) && (it.state in 1..2 || it.voided) }
                .fold(BigInteger.ZERO) { a, w -> a + w.amount }
            reserved > shown
        }
        return if (hidden) {
            listOf("Savings has money held for a withdrawal this app can't list. If you didn't ask for it, lock Savings now.")
        } else {
            emptyList()
        }
    }

    private fun checkingOf(s: AccountSnapshot, t: Token): BigInteger =
        if (t.key == AssetUM.ETH) s.ethBalance else s.tokenBalances.of(t.address)

    /** Each coin across both accounts (Savings never holds ETH). */
    private fun assets(s: AccountSnapshot, cur: VaultView?): List<AssetUM> = COINS.map { t ->
        val checking = checkingOf(s, t)
        val savings = if (t.key == AssetUM.ETH || cur == null) null else cur.balances.of(t.address)
        val available = if (savings == null) null else cur?.available?.of(t.address)
        val total = checking + (savings ?: BigInteger.ZERO)
        AssetUM(
            key = t.key,
            symbol = t.symbol,
            name = t.name,
            total = fmt(total, t),
            totalFiat = usd(total, t)?.let(::usdText),
            checking = fmt(checking, t),
            checkingFiat = usd(checking, t)?.let(::usdText),
            savings = savings?.let { fmt(it, t) },
            savingsFiat = savings?.let { usd(it, t) }?.let(::usdText),
            savingsAvailable = available?.let { fmt(it, t) },
            inChecking = checking.signum() > 0,
            inSavings = (savings ?: BigInteger.ZERO).signum() > 0,
        )
    }

    /** "2,000 PWTEST + 50.00 USDC" (only what's there), or "0" for nothing. */
    private fun moneyList(amounts: Map<String, BigInteger>): String =
        SepoliaConfig.TOKENS.filter { amounts.of(it.address).signum() > 0 }.joinToString(" + ") { money(amounts.of(it.address), it) }.ifEmpty { "0" }

    /** Who an address is, in words, when it's one of the user's own. */
    private fun label(address: String?, me: String, s: AccountSnapshot): String = when {
        address == null -> ""
        address.equals(me, ignoreCase = true) -> "Your Checking"
        address.equals(phoneKey, ignoreCase = true) -> "This phone"
        address.equals(current(s)?.address, ignoreCase = true) -> "Your Savings"
        s.vaults.any { it.address.equals(address, ignoreCase = true) } -> "Your previous Savings"
        else -> short(address)
    }

    private fun VaultView.toUM(me: String, now: Long, s: AccountSnapshot): VaultUM {
        val alerts = buildList {
            if (pendingPrimary != ZERO_ADDRESS) {
                add("A spending-key replacement to ${groupedAddress(pendingPrimary)} is scheduled. If it wasn't you, your backup key may be stolen: move everything to your safe haven now.")
            }
            if (vetoUntil > now) {
                add("Your spending key was replaced. Until ${time(vetoUntil)} you can still lock, cancel, or move everything to your safe haven.")
            }
        }
        val backupIsThisPhone = phoneKey?.equals(recovery, ignoreCase = true) == true
        val held = SepoliaConfig.TOKENS.any { available.of(it.address) < balances.of(it.address) }
        return VaultUM(
            address = address,
            balance = balance(SepoliaConfig.TOKENS.map { it to balances.of(it.address) }),
            available = if (held) balance(SepoliaConfig.TOKENS.map { it to available.of(it.address) }).headline + " available" else null,
            partlyHeld = held,
            hold = holdText(delaySeconds),
            locked = locked,
            isPrimary = primary.equals(me, ignoreCase = true),
            recovery = recovery,
            backupIsThisPhone = backupIsThisPhone,
            haven = haven.takeIf { it != ZERO_ADDRESS },
            alerts = alerts,
            pending = withdrawals.filter { it.state in 1..2 }.map { it.toUM(me, now, delaySeconds, s, address) },
            activity = activity.map { it.toUM(me, s, address) },
            backupChange = policy?.takeIf { it.kind == POLICY_NEW_RECOVERY }?.let { p ->
                BackupChangeUM(
                    newBackup = p.newAddress,
                    approved = p.approved,
                    waitText = if (now < p.eta) "Takes effect after ${time(p.eta)}" else null,
                    canFinish = p.approved && now >= p.eta,
                    canApproveFromPhone = !p.approved && backupIsThisPhone && !p.proposer.equals(phoneKey, ignoreCase = true),
                )
            },
            disputed = disputed,
            // An older vault (no releaseNow) that this phone backs: offer the new version.
            upgradeAvailable = !canReleaseNow && backupIsThisPhone && primary.equals(me, ignoreCase = true) && !locked,
        )
    }

    private fun WithdrawalView.toUM(me: String, now: Long, holdSeconds: Long, s: AccountSnapshot, vault: String): WithdrawalUM {
        val span = (deadline - requestedAt).coerceAtLeast(holdSeconds).coerceAtLeast(1)
        return WithdrawalUM(
            id = id.toString(),
            amount = money(amount, coin(token)),
            to = to,
            toLabel = if (toHaven) "Your safe haven" else label(to, me, s),
            toHaven = toHaven,
            isFinal = state == 2,
            countdown = if (state == 1 && deadline > now) "Arrives in ${duration(deadline - now)}" else null,
            progress = ((now - requestedAt).toFloat() / span).coerceIn(0f, 1f),
            deadline = time(deadline),
            // The contract decides; these only choose which buttons to offer.
            canCancel = state == 1 && (!toHaven || initiator.equals(me, ignoreCase = true)),
            canRelease = state == 2,
            canReleaseNow = canReleaseNow(s, vault, me),
            disputed = disputed,
            url = SepoliaConfig.explorerWithdrawal(vault, id.toString()),
        )
    }

    /**
     * Whether this phone can release it now with its backup key (the contract's releaseNow): a live withdrawal
     * the card asked for to its own address, from a current-factory vault backed by this phone, outside any
     * lock, key replacement or veto window. The contract checks all of it again.
     */
    private fun WithdrawalView.canReleaseNow(s: AccountSnapshot, vault: String, me: String): Boolean {
        val v = s.vaults.firstOrNull { it.address.equals(vault, ignoreCase = true) } ?: return false
        return state in 1..2 && !voided && !toHaven && v.canReleaseNow && !v.locked &&
            v.primary.equals(me, ignoreCase = true) && to.equals(v.primary, ignoreCase = true) && initiator.equals(v.primary, ignoreCase = true) &&
            phoneKey?.equals(v.recovery, ignoreCase = true) == true &&
            v.pendingPrimary == ZERO_ADDRESS && v.vetoUntil <= chainNow()
    }

    /** A protected payment from (or to) Checking, through the escrow. */
    private fun PaymentView.toUM(me: String, now: Long, s: AccountSnapshot): WithdrawalUM {
        val incoming = !sender.equals(me, ignoreCase = true)
        val other = if (incoming) sender else recipient
        val span = (deadline - createdAt).coerceAtLeast(1)
        return WithdrawalUM(
            id = id.toString(),
            source = Source.Checking,
            incoming = incoming,
            amount = money(amount, coin(token)),
            to = other,
            toLabel = label(other, me, s),
            toHaven = false,
            isFinal = state == 2,
            countdown = if (state == 1 && deadline > now) "Arrives in ${duration(deadline - now)}" else null,
            progress = ((now - createdAt).toFloat() / span).coerceIn(0f, 1f),
            deadline = time(deadline),
            canCancel = state == 1 && !incoming,
            canRelease = state == 2,
            disputed = disputed,
            url = "${SepoliaConfig.EXPLORER}/payment/$id",
        )
    }

    private fun ActivityView.toUM(me: String, s: AccountSnapshot, vault: String): ActivityUM {
        val t = coin(token ?: SepoliaConfig.TOKEN)
        val plus = amount?.let { "+${money(it, t)}" }
        val minus = amount?.let { "−${money(it, t)}" }
        val who = when {
            by == null -> ""
            by.equals(me, ignoreCase = true) -> "by your card"
            by.equals(phoneKey, ignoreCase = true) -> "by this phone"
            else -> "by ${short(by)}"
        }
        // Still on its way: shown as waiting, never as done (an incoming one can still be cancelled by its sender).
        val waiting = when (kind) {
            ActivityView.Kind.WithdrawalRequested ->
                s.vaults.firstOrNull { it.address.equals(vault, ignoreCase = true) }?.withdrawals?.firstOrNull { it.id == withdrawalId }?.state in 1..2
            ActivityView.Kind.PaymentSent, ActivityView.Kind.PaymentReceived -> s.payments.firstOrNull { it.id == withdrawalId }?.state in 1..2
            else -> false
        }
        data class Row(val title: String, val detail: String, val amount: String?, val icon: ActivityUM.Icon, val tone: ActivityUM.Tone)
        val row = when (kind) {
            // Between your own accounts: neither income nor spending.
            ActivityView.Kind.Deposited -> if (by.equals(me, ignoreCase = true)) {
                Row("From Checking", "Moved to Savings", plus, ActivityUM.Icon.In, ActivityUM.Tone.Neutral)
            } else {
                Row("Received", "from ${label(by, me, s)}", plus, ActivityUM.Icon.In, ActivityUM.Tone.Good)
            }
            ActivityView.Kind.WithdrawalRequested ->
                Row("To ${label(address, me, s)}", if (waiting) "On its way · from Savings" else "From Savings", minus, ActivityUM.Icon.Out, ActivityUM.Tone.Warning)
            ActivityView.Kind.HavenWithdrawalRequested -> Row("To your safe haven", "Move #$withdrawalId started", null, ActivityUM.Icon.Out, ActivityUM.Tone.Warning)
            ActivityView.Kind.WithdrawalCancelled -> Row("Payment #$withdrawalId cancelled", "Returned to Savings", null, ActivityUM.Icon.Returned, ActivityUM.Tone.Good)
            ActivityView.Kind.WithdrawalExecuted -> Row("Payment #$withdrawalId delivered", "", null, ActivityUM.Icon.Delivered, ActivityUM.Tone.Neutral)
            ActivityView.Kind.VoidedReleased -> Row("Payment #$withdrawalId stopped", "Returned to Savings", null, ActivityUM.Icon.Returned, ActivityUM.Tone.Good)
            ActivityView.Kind.Locked -> Row("Savings locked", who, null, ActivityUM.Icon.Lock, ActivityUM.Tone.Danger)
            ActivityView.Kind.Unlocked -> Row("Savings unlocked", who, null, ActivityUM.Icon.Unlock, ActivityUM.Tone.Neutral)
            ActivityView.Kind.ReplacementScheduled -> Row("Spending-key replacement scheduled", "to ${short(address)}", null, ActivityUM.Icon.Key, ActivityUM.Tone.Danger)
            ActivityView.Kind.ReplacementCancelled -> Row("Spending-key replacement cancelled", "", null, ActivityUM.Icon.Key, ActivityUM.Tone.Good)
            ActivityView.Kind.PrimaryReplaced -> Row("Spending key replaced", "now ${short(address)}", null, ActivityUM.Icon.Key, ActivityUM.Tone.Danger)
            ActivityView.Kind.RecoveryReplaced -> Row("Backup key replaced", "now ${label(address, me, s)}", null, ActivityUM.Icon.Key, ActivityUM.Tone.Warning)
            ActivityView.Kind.HavenReplaced -> Row("Safe haven changed", "now ${short(address)}", null, ActivityUM.Icon.Key, ActivityUM.Tone.Warning)
            ActivityView.Kind.DelayChanged -> Row("Hold period changed", "", null, ActivityUM.Icon.Key, ActivityUM.Tone.Warning)
            ActivityView.Kind.SecurityChange -> Row("Security setting updated", "", null, ActivityUM.Icon.Key, ActivityUM.Tone.Neutral)
            ActivityView.Kind.CheckingSent -> Row("To ${label(address, me, s)}", "From Checking · instant", minus, ActivityUM.Icon.Out, ActivityUM.Tone.Neutral)
            ActivityView.Kind.CheckingReceived -> Row("Received in Checking", "from ${label(address, me, s)}", plus, ActivityUM.Icon.In, ActivityUM.Tone.Good)
            ActivityView.Kind.PaymentSent ->
                Row("To ${label(address, me, s)}", if (waiting) "On its way · you can cancel" else "From Checking · protected", minus, ActivityUM.Icon.Out, ActivityUM.Tone.Warning)
            ActivityView.Kind.PaymentReceived -> if (waiting) {
                Row("From ${label(address, me, s)}", "On its way · the sender can still cancel", plus, ActivityUM.Icon.In, ActivityUM.Tone.Neutral)
            } else {
                Row("From ${label(address, me, s)}", "To Checking · protected", plus, ActivityUM.Icon.In, ActivityUM.Tone.Good)
            }
            ActivityView.Kind.PaymentCancelled -> {
                val mine = s.payments.firstOrNull { it.id == withdrawalId }?.sender?.equals(me, ignoreCase = true) != false
                Row("Payment #$withdrawalId cancelled", if (mine) "Returned to Checking" else "Returned to the sender", null, ActivityUM.Icon.Returned, ActivityUM.Tone.Good)
            }
            ActivityView.Kind.PaymentSettled -> Row("Payment #$withdrawalId delivered", "", null, ActivityUM.Icon.Delivered, ActivityUM.Tone.Neutral)
        }
        // Block times are about 12 s apart on Sepolia; good enough for "how long ago".
        val ago = (snapshotBlock - block).toLong().coerceAtLeast(0) * SECONDS_PER_BLOCK
        // A payment opens its own page (countdown, destination, address check); anything else its transaction.
        val url = when {
            withdrawalId != null && kind in ESCROW_KINDS -> "${SepoliaConfig.EXPLORER}/payment/$withdrawalId"
            withdrawalId != null && vault.isNotEmpty() -> SepoliaConfig.explorerWithdrawal(vault, withdrawalId.toString())
            // The hash comes from an endpoint: only a real one goes into a link.
            TX_HASH.matches(txHash) -> SepoliaConfig.EXPLORER_TX + txHash
            else -> SepoliaConfig.EXPLORER
        }
        return ActivityUM(row.title, row.detail.trim(), ago(ago), row.amount, url, row.icon, row.tone, if (vault.isEmpty()) Source.Checking else Source.Savings)
    }

    // ----------------------------------------------------------------- the guided flow

    /** Simulates [calls], then shows the Review sheet with exactly what will be signed. */
    @Suppress("LongParameterList")
    private fun review(
        title: String,
        rows: List<Pair<String, String>>,
        calls: List<Call>,
        doneTitle: String,
        by: By = By.Card,
        step: String? = null,
        note: String? = null,
        danger: Boolean = false,
        recipient: String? = null,
        then: (suspend () -> Unit)? = null,
    ) {
        val acct = account ?: return
        val from = when (by) {
            By.Card -> acct.address
            By.Phone -> phoneKey ?: return sheet(SheetUM.Result(ok = false, title = "Not done", text = PhoneKeyError.Missing().message.orEmpty(), url = null))
        }
        modelScope.launch {
            busy = true
            sheet(SheetUM.Working(title, "Checking with Sepolia…", holdCard = false))
            try {
                val prepared = if (demo != null) {
                    calls.map { c -> TxSender.Prepared(Eip1559Tx(SepoliaConfig.CHAIN_ID, BigInteger.ZERO, DEMO_FEE, DEMO_FEE, DEMO_GAS, c.to, c.value, c.data), DEMO_GAS * DEMO_FEE) }
                } else {
                    calls.mapIndexed { i, c -> sender.prepare(from, c.to, c.data, c.value, nonceOffset = i) }
                }
                val fee = prepared.fold(BigInteger.ZERO) { acc, p -> acc + p.maxCostWei }
                if (by == By.Phone && phoneKeyWei < fee) {
                    sheet(
                        SheetUM.Result(
                            ok = false,
                            title = "This phone needs a little Sepolia ETH",
                            text = "The backup key pays its own network fee (up to ${fmt(fee, FEE_DECIMALS)} ETH). Send some to\n${groupedAddress(from)}",
                            url = null,
                        ),
                    )
                    return@launch
                }
                pending = Pending(prepared, by, from, doneTitle, then, recipient)
                sheet(
                    SheetUM.Review(
                        title = title,
                        step = step,
                        rows = rows + ("Network" to "${SepoliaConfig.NAME} · chain ${SepoliaConfig.CHAIN_ID}"),
                        note = note,
                        fee = "Max network fee ${fmt(fee, FEE_DECIMALS)} Sepolia ETH" + if (by == By.Phone) ", paid by this phone's backup key" else "",
                        danger = danger,
                        withPhone = by == By.Phone,
                    ),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                sheet(SheetUM.Result(ok = false, title = "The contract would refuse this", text = reason(e), url = null))
            } finally {
                busy = false
            }
        }
    }

    override fun onSign() {
        val p = pending ?: return
        val acct = account ?: return
        val title = (state.value.sheet as? SheetUM.Review)?.title ?: p.doneTitle
        pending = null
        modelScope.launch {
            busy = true
            try {
                val d = demo
                if (d != null) {
                    sheet(SheetUM.Working(title, if (p.by == By.Card) "Demo: no card needed." else "Demo: no fingerprint needed.", holdCard = p.by == By.Card))
                    delay(DEMO_STEP_MS)
                    p.txs.forEach { d.apply(p.from, it.tx.to, it.tx.data, it.tx.value) }
                    p.recipient?.let(::rememberRecipient)
                    refresh()
                    if (p.then != null) p.then.invoke() else sheet(SheetUM.Result(ok = true, title = p.doneTitle, text = "Demo: nothing was sent.", url = null))
                    return@launch
                }
                val hashes = p.txs.map { it.tx.signingHash() }
                val signatures = when (p.by) {
                    By.Card -> {
                        sheet(SheetUM.Working(title, "Hold your card to the back of the phone until it's done.", holdCard = true))
                        signer.signAll(params.userWalletId, acct, hashes)
                    }
                    By.Phone -> {
                        sheet(SheetUM.Working(title, "Confirm with your fingerprint.", holdCard = false))
                        phoneKeys.sign(acct.address, PhoneKeyStore.Prompt(title, "Signed by this phone's backup key"), hashes)
                    }
                }
                var lastHash = ""
                p.txs.zip(signatures).forEachIndexed { i, (prepared, rsv) ->
                    val progress = if (p.txs.size > 1) " (${i + 1} of ${p.txs.size})" else ""
                    sheet(SheetUM.Working(title, "Signed. Sending to Sepolia and waiting for it to be included…$progress", holdCard = false))
                    lastHash = sender.broadcastAndWait(prepared.tx.signed(rsv.yParity, rsv.r, rsv.s), expectedFrom = p.from)
                }
                p.recipient?.let(::rememberRecipient)
                refresh()
                if (p.then != null) {
                    p.then.invoke()
                } else {
                    sheet(SheetUM.Result(ok = true, title = p.doneTitle, text = "Confirmed on Sepolia.", url = SepoliaConfig.EXPLORER_TX + lastHash))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SafeChainSignerError.Cancelled) {
                sheet(SheetUM.None)
            } catch (e: PhoneKeyError.Cancelled) {
                sheet(SheetUM.None)
            } catch (e: NotMinedYet) {
                // It was sent: saying "not done" would invite a second, duplicate payment.
                p.recipient?.let(::rememberRecipient)
                sheet(
                    SheetUM.Result(
                        ok = true,
                        title = "Sent, not confirmed yet",
                        text = "It's on its way but Sepolia hasn't included it yet. Don't send it again: check it on the explorer, it may go through in a few minutes.",
                        url = SepoliaConfig.EXPLORER_TX + e.txHash,
                    ),
                )
                refresh()
            } catch (e: Exception) {
                sheet(SheetUM.Result(ok = false, title = "Not done", text = reason(e), url = null))
                refresh()
            } finally {
                busy = false
            }
        }
    }

    override fun onCloseSheet() {
        pending = null
        sheet(SheetUM.None)
    }

    // ----------------------------------------------------------------- setup: this phone as the backup

    override fun onSetupCreate() {
        val hours = state.value.setup.delayHours.toLongOrNull()?.takeIf { it in MIN_HOURS..MAX_HOURS }
            ?: return setupError("Protection period: 24 to 720 hours.")
        createWithPhoneBackup(hours, isMove = false)
    }

    /** From a vault whose backup isn't this phone (another wallet, or a lost phone): a new vault, then move. */
    override fun onMoveToNewVault() {
        val v = current() ?: return
        createWithPhoneBackup(v.delaySeconds / HOUR, isMove = true)
    }

    /**
     * One fingerprint, one card tap. This phone's backup key signs the factory's recovery consent itself (the
     * digest is computed here), then the card signs creating the vault and, if the phone key has no ETH for
     * emergency fees yet, a small top-up — both in the same tap.
     */
    private fun createWithPhoneBackup(hours: Long, isMove: Boolean) {
        val acct = account ?: return
        setupError(null)
        modelScope.launch {
            busy = true
            try {
                if (demo == null) phoneKeys.checkAvailable()
                val newSalt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
                val delay = hours * HOUR
                fun digest(phone: String) = Eip712.recoveryConsentDigest(acct.address, phone, delay, ZERO_ADDRESS, newSalt)
                val prompt = PhoneKeyStore.Prompt("Make this phone your backup key", "Only needed if your card is ever lost or stolen")
                val existing = phoneKey
                val (phone, consent) = if (demo != null) {
                    DemoChain.PHONE to com.tangem.feature.safechain.chain.Rsv(ByteArray(32), ByteArray(32), 0)
                } else if (existing == null) {
                    phoneKeys.create(acct.address, prompt) { listOf(digest(it)) }.let { (a, sigs) -> a to sigs.single() }
                } else {
                    existing to phoneKeys.sign(acct.address, prompt, listOf(digest(existing))).single()
                }
                phoneKey = phone
                state.update { it.copy(phoneKey = phone) }
                val calls = buildList {
                    add(Call(SepoliaConfig.FACTORY, SafeChainCalls.createVault(acct.address, phone, delay, newSalt, consent.toEip712Bytes(), ZERO_ADDRESS, ByteArray(0))))
                    if (phoneKeyWei < PHONE_GAS_LOW) add(Call(phone, ByteArray(0), PHONE_GAS_TOP_UP))
                }
                busy = false
                review(
                    title = if (isMove) "New Savings, protected by this phone" else "Create Protected Savings",
                    rows = buildList {
                        add("Spends" to "This card\n${groupedAddress(acct.address)}")
                        add("Backup key" to "This phone (your fingerprint)\n${groupedAddress(phone)}")
                        add("Protection" to "${hours}h: every payment waits this long, and you can cancel it until then")
                        if (calls.size > 1) add("Emergency fees" to "${fmt(PHONE_GAS_TOP_UP)} Sepolia ETH to this phone's backup key")
                    },
                    calls = calls,
                    doneTitle = "Protected Savings created",
                    note = if (calls.size > 1) "One tap: your card signs both." else null,
                ) {
                    state.update { it.copy(setup = SetupUM()) }
                    val text = if (isMove || (snapshot?.vaults?.size ?: 0) > 1) {
                        "Next, move your money over: tap Move on \"Old Savings\" under your accounts. It waits out the old hold once."
                    } else {
                        "Your card spends. This phone can stop every payment if your card is ever lost."
                    }
                    sheet(SheetUM.Result(ok = true, title = "Protected Savings is ready", text = text, url = null))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PhoneKeyError.Cancelled) {
                sheet(SheetUM.None)
            } catch (e: PhoneKeyError) {
                setupError(e.message)
                sheet(SheetUM.Result(ok = false, title = "Not done", text = e.message.orEmpty(), url = null))
            } catch (e: Exception) {
                sheet(SheetUM.Result(ok = false, title = "Not done", text = reason(e), url = null))
            } finally {
                busy = false
            }
        }
    }

    // ----------------------------------------------------------------- setup: another wallet as the backup

    override fun onSetupUseOtherWallet(other: Boolean) = state.update { it.copy(setup = it.setup.copy(otherWallet = other, error = null)) }

    override fun onSetupMakeLink() {
        val acct = account ?: return
        val s = state.value.setup
        val recovery = parseAddress(s.recovery) ?: return setupError("That isn't a valid address. Paste the backup wallet's full address.")
        if (recovery.equals(acct.address, ignoreCase = true)) {
            return setupError("The backup key must be a different key from this card, on another device.")
        }
        val hours = s.delayHours.toLongOrNull()?.takeIf { it in MIN_HOURS..MAX_HOURS }
            ?: return setupError("Protection period: 24 to 720 hours.")
        val newSalt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        salt = newSalt
        // The same ConsentRequest the web consent page reads (SafeChain web/src/lib/consent.ts).
        val request = """{"kind":"create","primary":"${acct.address}","recovery":"$recovery","withdrawalDelay":"${hours * HOUR}","haven":"$ZERO_ADDRESS","salt":"${Hex.encode(newSalt)}"}"""
        state.update { it.copy(setup = s.copy(recovery = recovery, consentLink = consentLink(request), signature = "", error = null)) }
    }

    override fun onSetupCreateWithOtherWallet() {
        val acct = account ?: return
        val s = state.value.setup
        val recovery = parseAddress(s.recovery) ?: return setupError("That isn't a valid address.")
        val currentSalt = salt ?: return setupError("Make the consent link first.")
        val sig = parseSignature(s.signature)
            ?: return setupError("Paste the backup wallet's signature from the consent page: 0x followed by 130 characters.")
        val hours = s.delayHours.toLongOrNull()?.takeIf { it in MIN_HOURS..MAX_HOURS }
            ?: return setupError("Protection period: 24 to 720 hours.")
        setupError(null)
        review(
            title = "Create Protected Savings",
            rows = listOf(
                "Spends" to "This card\n${groupedAddress(acct.address)}",
                "Backup key" to groupedAddress(recovery),
                "Protection" to "${hours}h: every payment waits this long, and you can cancel it until then",
            ),
            calls = listOf(Call(SepoliaConfig.FACTORY, SafeChainCalls.createVault(acct.address, recovery, hours * HOUR, currentSalt, sig, ZERO_ADDRESS, ByteArray(0)))),
            doneTitle = "Protected Savings created",
        ) {
            state.update { it.copy(setup = SetupUM()) }
            sheet(SheetUM.Result(ok = true, title = "Protected Savings is ready", text = "Your card spends; your backup wallet can stop payments and lock.", url = null))
        }
    }

    override fun onSetupRecoveryChange(value: String) = state.update { it.copy(setup = it.setup.copy(recovery = value, consentLink = null, error = null)) }
    override fun onSetupDelayChange(value: String) = state.update { it.copy(setup = it.setup.copy(delayHours = value, consentLink = null, error = null)) }
    override fun onSetupSignatureChange(value: String) = state.update { it.copy(setup = it.setup.copy(signature = value.trim(), error = null)) }
    private fun setupError(text: String?) = state.update { it.copy(setup = it.setup.copy(error = text)) }

    // ----------------------------------------------------------------- everyday

    override fun onOpenAccount(source: Source) = state.update { it.copy(page = source) }
    override fun onCloseAccount() = state.update { it.copy(page = null) }

    override fun onOpenMove() = sheet(SheetUM.Move(savingsLocked = current()?.locked == true))

    override fun onMoveToSavings() {
        val assets = state.value.assets.filter { !it.isEth }
        sheet(SheetUM.AddFunds(token = biggest(assets.filter { it.inChecking }) ?: assets.first().key, assets = assets))
    }

    override fun onMoveToChecking() {
        val assets = state.value.assets.filter { !it.isEth }
        sheet(SheetUM.Withdraw(token = biggest(assets.filter { it.inSavings }) ?: assets.first().key, assets = assets, toChecking = true))
    }

    override fun onOpenWithdraw() {
        val s = snapshot ?: return
        val v = current()
        val savingsUsable = v != null && !v.locked && v.primary.equals(account?.address, ignoreCase = true)
        // Start from Checking, like a bank: it's the everyday account. Savings is one tap away.
        val checkingHasTokens = s.tokenBalances.values.any { it.signum() > 0 }
        onOpenSend(if (checkingHasTokens || !savingsUsable) Source.Checking else Source.Savings, null)
    }

    override fun onOpenSend(from: Source, token: String?) {
        val v = current()
        val ui = state.value
        val base = SheetUM.Send(
            from = from,
            token = "",
            assets = ui.assets,
            checkingTotal = ui.checking.headline,
            savingsTotal = ui.vault?.balance?.headline ?: "",
            savingsHold = v?.let { holdText(it.delaySeconds) },
            savingsLocked = v?.locked == true,
        )
        sheet(base.copy(token = token?.takeIf { k -> base.choices.any { it.key == k } } ?: biggest(base.choices) ?: base.assets.first().key))
    }

    /** The token worth the most in this list (dollars first, then the largest unpriced amount), ETH last. */
    private fun biggest(choices: List<AssetUM>): String? = choices
        .sortedWith(
            compareBy<AssetUM> { it.isEth }
                .thenByDescending { it.totalFiat != null && it.total != "0.00" }
                .thenByDescending { it.total.replace(",", "").toBigDecimalOrNull() ?: BigDecimal.ZERO },
        )
        .firstOrNull()?.key

    override fun onSendFrom(source: Source) = updateSend { sh ->
        val moved = sh.copy(from = source, error = null)
        // Savings never holds ETH: switch to what it does hold rather than leave a dead choice.
        if (moved.choices.any { it.key == sh.token }) moved else moved.copy(token = biggest(moved.choices) ?: sh.token, amount = "")
    }

    override fun onTokenChange(key: String) = state.update {
        when (val sh = it.sheet) {
            is SheetUM.Send -> it.copy(sheet = sh.copy(token = key, amount = "", error = null))
            is SheetUM.AddFunds -> it.copy(sheet = sh.copy(token = key, amount = "", error = null))
            is SheetUM.Withdraw -> it.copy(sheet = sh.copy(token = key, amount = "", error = null))
            else -> it
        }
    }
    override fun onHoldChange(hours: String) = updateSend { it.copy(hold = hours, error = null) }
    override fun onCustomHoursChange(value: String) = updateSend {
        // Digits and one decimal point ("0.1" hours, "1,5" on comma keyboards).
        val clean = value.replace(',', '.').filter { c -> c.isDigit() || c == '.' }
        val oneDot = clean.substringBefore('.') + clean.substringAfter('.', "").let { r -> if ('.' in clean) ".${r.replace(".", "")}" else "" }
        it.copy(customHours = oneDot.take(6), error = null)
    }
    override fun onCustomUnitChange(unit: HoldUnit) = updateSend { it.copy(customUnit = unit, error = null) }

    private fun updateSend(f: (SheetUM.Send) -> SheetUM.Send) = state.update {
        (it.sheet as? SheetUM.Send)?.let { sh -> it.copy(sheet = f(sh)) } ?: it
    }

    override fun onOpenReceive() {
        val me = account?.address ?: return
        // Savings' address is offered only once agreeing endpoints have confirmed it (see depositTarget).
        sheet(SheetUM.Receive(checking = me, savings = depositTarget()?.address, into = state.value.page ?: Source.Checking))
    }

    override fun onReceiveInto(source: Source) = state.update {
        (it.sheet as? SheetUM.Receive)?.let { r -> it.copy(sheet = r.copy(into = source)) } ?: it
    }

    override fun onAmountChange(value: String) = state.update {
        // A comma is the decimal point (European keyboards type "1,5"): never a thousands separator, which
        // used to turn "1,5" into 15 (security review 2026-10-24). One decimal point at most.
        val clean = value.replace(',', '.').filter { c -> c.isDigit() && c in '0'..'9' || c == '.' }
        val amount = clean.substringBefore('.') + if ('.' in clean) "." + clean.substringAfter('.').replace(".", "") else ""
        when (val sh = it.sheet) {
            is SheetUM.AddFunds -> it.copy(sheet = sh.copy(amount = amount, error = null))
            is SheetUM.Withdraw -> it.copy(sheet = sh.copy(amount = amount, error = null))
            is SheetUM.Send -> it.copy(sheet = sh.copy(amount = amount, error = null))
            else -> it
        }
    }

    override fun onDestinationChange(value: String) = state.update {
        when (val sh = it.sheet) {
            is SheetUM.Withdraw -> it.copy(sheet = sh.copy(to = value.trim(), error = null))
            is SheetUM.Send -> it.copy(sheet = sh.copy(to = value.trim(), error = null))
            else -> it
        }
    }

    override fun onMax() = state.update {
        // The field takes plain numbers: no grouping commas ("17,700" → "17700").
        when (val sh = it.sheet) {
            is SheetUM.AddFunds -> it.copy(sheet = sh.copy(amount = sh.max.replace(",", ""), error = null))
            is SheetUM.Withdraw -> it.copy(sheet = sh.copy(amount = sh.max.replace(",", ""), error = null))
            is SheetUM.Send -> it.copy(sheet = sh.copy(amount = sh.max.replace(",", ""), error = null))
            else -> it
        }
    }

    override fun onContinue() {
        if (blockedByDisagreement()) return
        (state.value.sheet as? SheetUM.Send)?.let { return send(it) }
        val v = current() ?: return
        when (val sh = state.value.sheet) {
            is SheetUM.AddFunds -> {
                val t = coin(sh.token)
                val amount = parseAmount(sh.amount, t.decimals) ?: return sheet(sh.copy(error = "Enter an amount, for example 100."))
                if (amount > (snapshot?.tokenBalances?.of(t.address) ?: BigInteger.ZERO)) {
                    return sheet(sh.copy(error = "Checking has ${sh.max} ${t.symbol}."))
                }
                if (depositTarget()?.address != v.address) {
                    return sheet(sh.copy(error = "Waiting for both data sources to confirm your Savings address. Try again in a moment."))
                }
                review(
                    title = "Move ${money(amount, t)} to Savings",
                    rows = listOf("Into" to "Your Protected Savings\n${groupedAddress(v.address)}", "Amount" to money(amount, t)),
                    // A plain transfer counts as a deposit: one tap, no separate approval.
                    calls = listOf(Call(t.address, SafeChainCalls.transfer(v.address, amount))),
                    doneTitle = "Moved ${money(amount, t)} to Savings",
                )
            }
            is SheetUM.Withdraw -> {
                val t = coin(sh.token)
                val amount = parseAmount(sh.amount, t.decimals) ?: return sheet(sh.copy(error = "Enter an amount, for example 100."))
                if (amount > v.available.of(t.address)) return sheet(sh.copy(error = "Only ${sh.max} ${t.symbol} is available."))
                val to = if (sh.toChecking) {
                    account?.address ?: return
                } else {
                    parseAddress(sh.to) ?: return sheet(sh.copy(error = "Paste the destination's full address (0x…)."))
                }
                if (to.equals(v.address, ignoreCase = true)) return sheet(sh.copy(error = "That's your Savings itself."))
                if (!sh.toChecking) contractDestination(to)?.let { return sheet(sh.copy(error = it)) }
                val hold = holdText(v.delaySeconds)
                val poison = if (sh.toChecking) null else lookAlikeWarning(to)
                review(
                    title = if (sh.toChecking) "Move ${money(amount, t)} to Checking" else "Send ${money(amount, t)}",
                    rows = listOfNotNull(
                        poison?.let { "⚠ Look-alike address" to it },
                        "To" to if (sh.toChecking) "Your Checking (this card)\n${groupedAddress(to)}" else groupedAddress(to),
                        "Arrives" to "In $hold. Until then you can cancel, and the money comes straight back.",
                    ),
                    note = "Check every character of the address. You can also check it from another device at safechain.rep.run.",
                    calls = listOf(Call(v.address, SafeChainCalls.requestWithdrawal(t.address, amount, to))),
                    doneTitle = "Moving · arrives in $hold",
                    danger = poison != null,
                    recipient = if (sh.toChecking) null else to,
                )
            }
            else -> Unit
        }
    }

    /** Send from Savings (its hold) or from Checking (instant, or a hold you choose through the escrow). */
    @Suppress("LongMethod", "ReturnCount")
    private fun send(sh: SheetUM.Send) {
        val s = snapshot ?: return
        val me = account?.address ?: return
        val t = coin(sh.token)
        val amount = parseAmount(sh.amount, t.decimals) ?: return sheet(sh.copy(error = "Enter an amount, for example 100."))
        val to = parseAddress(sh.to) ?: return sheet(sh.copy(error = "Paste the destination's full address (0x…). A mixed-case address must have the right capitals."))
        contractDestination(to)?.let { return sheet(sh.copy(error = it)) }
        val what = money(amount, t)
        val check = "Check every character of the address. You can also check it from another device at safechain.rep.run."
        // Address poisoning: a different address that starts and ends like one this card paid before.
        val poison = lookAlikeWarning(to)
        val poisonRow = poison?.let { "⚠ Look-alike address" to it }
        if (sh.from == Source.Savings) {
            val v = current() ?: return sheet(sh.copy(error = "Open Savings first."))
            if (v.locked) return sheet(sh.copy(error = "Savings is locked."))
            if (amount > v.available.of(t.address)) return sheet(sh.copy(error = "Only ${sh.max} ${t.symbol} is available in Savings."))
            if (to.equals(v.address, ignoreCase = true)) return sheet(sh.copy(error = "That's your Savings itself."))
            val hold = holdText(v.delaySeconds)
            return review(
                title = "Send $what from Savings",
                rows = listOfNotNull(
                    poisonRow,
                    "To" to groupedAddress(to),
                    "Arrives" to "In $hold. Until then you can cancel, and the money comes straight back to Savings.",
                ),
                note = check,
                calls = listOf(Call(v.address, SafeChainCalls.requestWithdrawal(t.address, amount, to))),
                doneTitle = "Sending · arrives in $hold",
                danger = poison != null,
                recipient = to,
            ) {
                val w = current()?.withdrawals?.firstOrNull { it.to.equals(to, ignoreCase = true) && it.amount == amount && it.state == 1 }
                sent("Sending · arrives in $hold", what, w?.deadline ?: (chainNow() + v.delaySeconds), w?.let { SepoliaConfig.explorerWithdrawal(v.address, it.id.toString()) })
            }
        }

        if (amount > checkingOf(s, t)) return sheet(sh.copy(error = "Only ${sh.max} ${t.symbol} is in Checking."))
        if (to.equals(me, ignoreCase = true)) return sheet(sh.copy(error = "That's this card's own address."))
        if (t.key == AssetUM.ETH) {
            // The escrow holds tokens only, so ETH always goes at once.
            return review(
                title = "Send $what from Checking",
                rows = listOfNotNull(poisonRow, "To" to groupedAddress(to), "Arrives" to "Immediately. ETH sends can't be cancelled."),
                note = check,
                calls = listOf(Call(to, ByteArray(0), amount)),
                doneTitle = "Sent",
                danger = poison != null,
                recipient = to,
            )
        }
        val seconds = if (sh.hold == HOLD_CUSTOM) {
            holdSeconds(sh.customHours, sh.customUnit)
                ?: return sheet(sh.copy(error = "Enter how long to hold it, for example 10 minutes or 1.5 hours."))
        } else {
            sh.hold.toLong() * HOUR
        }
        if (seconds != 0L && seconds !in MIN_HOLD_SECONDS..MAX_HOLD_SECONDS) {
            return sheet(sh.copy(error = "A hold is 5 minutes to 30 days."))
        }

        if (seconds == 0L) {
            return review(
                title = "Send $what from Checking",
                rows = listOfNotNull(poisonRow, "To" to groupedAddress(to), "Arrives" to "Immediately. An instant send can't be cancelled."),
                note = "$check Choose a hold instead if you might want to undo it.",
                calls = listOf(Call(t.address, SafeChainCalls.transfer(to, amount))),
                doneTitle = "Sent",
                danger = poison != null,
                recipient = to,
            )
        }

        val hold = holdText(seconds)
        val allowance = s.escrowAllowances.of(t.address)
        val pay = {
            review(
                title = "Send $what from Checking",
                step = if (allowance < amount) "Step 2 of 2" else null,
                rows = listOfNotNull(
                    poisonRow,
                    "To" to groupedAddress(to),
                    "Arrives" to "In $hold. Until then you can cancel, and the money comes straight back to Checking.",
                    "Held by" to "SafeChain's payment escrow (no owner, can't be changed)\n${groupedAddress(SepoliaConfig.ESCROW)}",
                ),
                note = check,
                calls = listOf(Call(SepoliaConfig.ESCROW, SafeChainCalls.createPayment(t.address, to, amount, seconds))),
                doneTitle = "Sending · arrives in $hold",
                danger = poison != null,
                recipient = to,
            ) {
                val p = snapshot?.payments?.firstOrNull { it.recipient.equals(to, ignoreCase = true) && it.amount == amount && it.state == 1 }
                sent("Sending · arrives in $hold", what, p?.deadline ?: (chainNow() + seconds), p?.let { "${SepoliaConfig.EXPLORER}/payment/${it.id}" })
            }
        }
        if (allowance >= amount) return pay()
        // Once per token: let the escrow take what Checking sends with a hold. It only ever takes from the card,
        // for a payment the card itself creates, so nobody else can use this approval.
        review(
            title = "Turn on protected ${t.symbol} payments",
            step = "Step 1 of 2 · once for ${t.symbol}",
            rows = listOf(
                "Allows" to "SafeChain's payment escrow to hold the ${t.symbol} you send from Checking with a hold, until it's delivered or you cancel",
                "Amount" to "Unlimited. The escrow only takes what you send in a payment you sign; nobody else can use this.",
                "Escrow" to groupedAddress(SepoliaConfig.ESCROW),
            ),
            note = "You won't be asked again for ${t.symbol}.",
            calls = listOf(Call(t.address, SafeChainCalls.approve(SepoliaConfig.ESCROW, MAX_UINT))),
            doneTitle = "Protected ${t.symbol} payments on",
        ) { pay() }
    }

    /**
     * Where this card has sent money before, to catch a look-alike destination: Savings withdrawals, protected
     * payments and instant sends.
     */
    private fun paidBefore(): List<String> {
        // Addresses this app signed payments to are remembered on the phone: on-chain lists can be flushed by
        // spam, and anyone can plant a zero-value "transfer from you" (security review 2026-10-24).
        val own = recipients().toList()
        val s = snapshot ?: return own
        val me = account?.address ?: return own
        return own +
            ownVaults(s).flatMap { v -> v.withdrawals.filter { it.initiator.equals(me, ignoreCase = true) && it.amount.signum() > 0 }.map { it.to } } +
            s.payments.filter { it.sender.equals(me, ignoreCase = true) }.map { it.recipient } +
            s.checkingActivity.filter { it.kind == ActivityView.Kind.CheckingSent && (it.amount ?: BigInteger.ZERO).signum() > 0 }.mapNotNull { it.address }
    }

    private fun lookAlikeWarning(to: String): String? = lookAlike(to, paidBefore())?.let { known ->
        "This address starts and ends like ${short(known)}, which you paid before, but the middle is different. " +
            "That's how address poisoning works. Only continue if the recipient gave you this exact address just now."
    }

    /**
     * Sent with a hold: the recipient can confirm it's theirs by looking up their own address, which they know
     * for certain, before it arrives. If it isn't there, the sender still has time to cancel.
     */
    private fun sent(title: String, what: String, arrivesAt: Long, url: String?) {
        val share = "I'm sending you $what (Sepolia test network) with SafeChain. It arrives ${time(arrivesAt)}, " +
            "and I can cancel it until then.\n\nPlease check it's coming to you: go to safechain.rep.run and search " +
            "your own address. You should see $what on its way, with a timer. If it's not there, tell me before it arrives."
        sheet(
            SheetUM.Result(
                ok = true,
                title = title,
                text = (if (demo != null) "Demo: nothing was sent. " else "") +
                    "Ask the recipient to search their own address at safechain.rep.run. They'll see it on its way, with the timer, and can tell you in time if it isn't there.",
                url = url,
                share = share,
            ),
        )
    }

    override fun onCancelPayment(id: String) {
        val p = snapshot?.payments?.firstOrNull { it.id.toString() == id } ?: return
        val amount = money(p.amount, coin(p.token))
        review(
            title = "Cancel this payment",
            rows = listOf("Amount" to amount, "Was going to" to groupedAddress(p.recipient)),
            note = "The money comes straight back to Checking.",
            calls = listOf(Call(SepoliaConfig.ESCROW, SafeChainCalls.cancelPayment(p.id))),
            doneTitle = "Cancelled",
        ) {
            sheet(SheetUM.Result(ok = true, title = "Cancelled", text = "$amount is back in Checking.", url = null))
        }
    }

    override fun onSettlePayment(id: String) {
        val p = snapshot?.payments?.firstOrNull { it.id.toString() == id } ?: return
        review(
            title = "Deliver payment #$id",
            rows = listOf("Amount" to money(p.amount, coin(p.token)), "To" to groupedAddress(p.recipient)),
            note = "It's final; this delivers it. Anyone can do this step, you just pay the fee.",
            calls = listOf(Call(SepoliaConfig.ESCROW, SafeChainCalls.settlePayment(p.id))),
            doneTitle = "Payment #$id delivered",
        )
    }

    override fun onCancelWithdrawal(id: String) {
        val v = current() ?: return
        val w = v.withdrawals.firstOrNull { it.id.toString() == id } ?: return
        val t = coin(w.token)
        val amount = money(w.amount, t)
        review(
            title = "Cancel this payment",
            rows = listOf("Amount" to amount, "Was going to" to groupedAddress(w.to)),
            note = "The money comes straight back to your balance.",
            calls = listOf(Call(v.address, SafeChainCalls.cancelWithdrawal(w.id))),
            doneTitle = "Cancelled",
        ) {
            val balance = current()?.available?.let { money(it.of(t.address), t) }
            sheet(
                SheetUM.Result(
                    ok = true,
                    title = "Cancelled",
                    text = "$amount is back in your balance." + (balance?.let { " Available now: $it." } ?: ""),
                    url = null,
                ),
            )
        }
    }

    /** Open a new Savings (current factory, this phone as backup, same hold); the old one moves over after. */
    override fun onUpgradeSavings() {
        if (blockedByDisagreement()) return
        val v = current() ?: return
        createWithPhoneBackup(maxOf(v.delaySeconds / HOUR, MIN_HOURS), isMove = true)
    }

    /** Both keys, no wait: this phone's backup key releases a move to the card's own Checking now. */
    override fun onReleaseNow(id: String) {
        val s = snapshot ?: return
        val me = account?.address ?: return
        val v = current() ?: return
        val w = v.withdrawals.firstOrNull { it.id.toString() == id }?.takeIf { it.canReleaseNow(s, v.address, me) } ?: return
        val amount = money(w.amount, coin(w.token))
        review(
            title = "Release $amount now",
            rows = listOf(
                "To" to "Your Checking (this card)\n${groupedAddress(w.to)}",
                "Instead of" to "Waiting until ${time(w.deadline)}",
                "Why it's safe" to "Your card asked for it and this phone agrees: both keys. It can only go to your own card.",
            ),
            calls = listOf(Call(v.address, SafeChainCalls.releaseNow(w.id))),
            doneTitle = "$amount is in Checking",
            by = By.Phone,
        )
    }

    override fun onReleaseWithdrawal(id: String) {
        val v = current() ?: return
        release(v, id)
    }

    private fun release(v: VaultView, id: String) {
        val w = v.withdrawals.firstOrNull { it.id.toString() == id } ?: return
        review(
            title = "Deliver payment #$id",
            rows = listOf("Amount" to money(w.amount, coin(w.token)), "To" to groupedAddress(w.to)),
            note = "It's final; this delivers it. Anyone can do this step, you just pay the fee.",
            calls = listOf(Call(v.address, SafeChainCalls.executeWithdrawal(w.id))),
            doneTitle = "Payment #$id delivered",
        )
    }

    // ----------------------------------------------------------------- emergencies and locks

    override fun onTopUpPhone() {
        val phone = phoneKey ?: return
        review(
            title = "Add emergency fees",
            rows = listOf(
                "To" to "This phone's backup key\n${groupedAddress(phone)}",
                "Amount" to "${fmt(PHONE_GAS_TOP_UP)} Sepolia ETH",
                "Why" to "So this phone can lock your Savings on its own if your card is ever lost.",
            ),
            calls = listOf(Call(phone, ByteArray(0), PHONE_GAS_TOP_UP)),
            doneTitle = "Emergency fees added",
        )
    }

    override fun onEmergencyLock() {
        val v = current() ?: return
        review(
            title = "Lock Savings from this phone",
            rows = listOf(
                "What happens" to "Every payment that isn't final yet is stopped, and the money stays. Nothing can be sent until you unlock with this phone.",
                "Savings" to groupedAddress(v.address),
            ),
            calls = listOf(Call(v.address, SafeChainCalls.lock())),
            doneTitle = "Savings locked",
            by = By.Phone,
            danger = true,
        ) {
            sheet(
                SheetUM.Result(
                    ok = true,
                    title = "Savings locked",
                    text = "Nothing can leave, and any payment on its way was stopped. When you're safe, unlock with this phone: stopped payments come back to your balance. If the card is gone for good, your Tangem backup card is the same spending key.",
                    url = null,
                ),
            )
        }
    }

    override fun onUnlock() {
        val v = current() ?: return
        // Payments the lock stopped still hold their reservation; release them in the same fingerprint, so
        // the money is spendable again the moment the vault unlocks.
        val stopped = v.withdrawals.filter { it.voided }
        review(
            title = "Unlock Savings",
            rows = buildList {
                add("Savings" to groupedAddress(v.address))
                if (stopped.isNotEmpty()) {
                    val sums = stopped.groupBy { it.token.lowercase() }.mapValues { (_, ws) -> ws.fold(BigInteger.ZERO) { a, w -> a + w.amount } }
                    add("Stopped payments" to "${stopped.size} (${moneyList(sums)}) come back to your balance")
                }
                add("After this" to "Your card can send again.")
            },
            calls = listOf(Call(v.address, SafeChainCalls.unlock())) + stopped.map { Call(v.address, SafeChainCalls.releaseVoided(it.id)) },
            doneTitle = "Savings unlocked",
            by = By.Phone,
        )
    }

    override fun onLock() {
        val v = current() ?: return
        review(
            title = "Lock Savings",
            rows = listOf("Savings" to groupedAddress(v.address)),
            note = "Every payment that isn't final yet is stopped, and nothing new can be sent. Only the backup key can unlock.",
            calls = listOf(Call(v.address, SafeChainCalls.lock())),
            doneTitle = "Savings locked",
            danger = true,
        )
    }

    override fun onMoveToHaven() {
        val v = current() ?: return
        if (v.haven == ZERO_ADDRESS) return
        val clear = v.withdrawals.filter { it.state == 1 && !it.toHaven }.map { it.id }
        review(
            title = "Move everything to your safe haven",
            rows = listOf("Safe haven" to groupedAddress(v.haven), "Cancels first" to "${clear.size} pending payment(s)"),
            note = "Only you can cancel this move. It arrives after the protection period.",
            // One move per token it holds; the first also clears the pending payments.
            calls = SepoliaConfig.TOKENS.filter { v.balances.of(it.address).signum() > 0 }.mapIndexed { i, t ->
                Call(v.address, SafeChainCalls.requestHavenWithdrawal(t.address, MAX_UINT, if (i == 0) clear else emptyList()))
            },
            doneTitle = "Moving everything to your safe haven",
            danger = true,
        )
    }

    // ----------------------------------------------------------------- backup key: switch to another wallet

    override fun onOpenOtherWallet() = sheet(SheetUM.OtherWallet())

    override fun onOtherWalletAddressChange(value: String) = updateOther { it.copy(address = value.trim(), link = null, error = null) }
    override fun onOtherWalletSignatureChange(value: String) = updateOther { it.copy(signature = value.trim(), error = null) }

    override fun onOtherWalletMakeLink() {
        val v = current() ?: return
        val acct = account ?: return
        val sh = state.value.sheet as? SheetUM.OtherWallet ?: return
        val address = parseAddress(sh.address) ?: return updateOther { it.copy(error = "Paste the other wallet's full address (0x…).") }
        if (address.equals(acct.address, ignoreCase = true) || address.equals(v.recovery, ignoreCase = true)) {
            return updateOther { it.copy(error = "That's already one of this vault's keys.") }
        }
        if (v.policy != null) return updateOther { it.copy(error = "Another security change is already waiting. Finish or cancel it first.") }
        // The vault's "rotate" consent, as the web consent page reads it (SafeChain web/src/lib/consent.ts).
        val request = """{"kind":"rotate","vault":"${v.address}","newRecovery":"$address","proposalNonce":"${v.policyNonce + BigInteger.ONE}"}"""
        updateOther { it.copy(address = address, link = consentLink(request), signature = "", error = null) }
    }

    override fun onOtherWalletContinue() {
        val v = current() ?: return
        val sh = state.value.sheet as? SheetUM.OtherWallet ?: return
        val address = parseAddress(sh.address) ?: return updateOther { it.copy(error = "Paste the other wallet's full address.") }
        val sig = parseSignature(sh.signature)
            ?: return updateOther { it.copy(error = "Paste the signature from the consent page: 0x followed by 130 characters.") }
        val nonce = v.policyNonce + BigInteger.ONE
        val waitHours = maxOf(v.delaySeconds, MIN_HOURS * HOUR) / HOUR
        review(
            title = "Make another wallet your backup key",
            step = "Step 1 of 2 · card",
            rows = listOf(
                "New backup key" to groupedAddress(address),
                "Replaces" to "This phone",
                "Then" to "This phone approves with your fingerprint. It takes effect ${waitHours}h later.",
            ),
            calls = listOf(Call(v.address, SafeChainCalls.proposeRecoveryChange(address, sig))),
            doneTitle = "Proposed",
        ) { approveFromPhone(nonce) }
    }

    override fun onApproveBackupChange() {
        val v = current() ?: return
        approveFromPhone(v.policyNonce)
    }

    private fun approveFromPhone(nonce: BigInteger) {
        val v = current() ?: return
        val p = v.policy ?: return
        review(
            title = "Approve the new backup key",
            step = "Step 2 of 2 · this phone",
            rows = listOf("New backup key" to groupedAddress(p.newAddress), "Takes effect" to "After ${time(p.eta)}"),
            calls = listOf(Call(v.address, SafeChainCalls.approvePolicyChange(nonce))),
            doneTitle = "Approved",
            by = By.Phone,
        ) {
            sheet(
                SheetUM.Result(
                    ok = true,
                    title = "Approved",
                    text = "Come back after ${time(p.eta)} and tap Finish. Until then this phone stays the backup key, and either key can cancel the change.",
                    url = null,
                ),
            )
        }
    }

    override fun onFinishBackupChange() {
        val v = current() ?: return
        val p = v.policy ?: return
        review(
            title = "Finish: new backup key",
            rows = listOf("New backup key" to groupedAddress(p.newAddress)),
            calls = listOf(Call(v.address, SafeChainCalls.executePolicyChange(v.policyNonce))),
            doneTitle = "Backup key changed",
        )
    }

    override fun onCancelBackupChange() {
        val v = current() ?: return
        review(
            title = "Cancel the backup-key change",
            rows = listOf("Savings" to groupedAddress(v.address)),
            calls = listOf(Call(v.address, SafeChainCalls.cancelPolicyChange())),
            doneTitle = "Change cancelled",
        )
    }

    private fun updateOther(f: (SheetUM.OtherWallet) -> SheetUM.OtherWallet) = state.update {
        (it.sheet as? SheetUM.OtherWallet)?.let { sh -> it.copy(sheet = f(sh)) } ?: it
    }

    // ----------------------------------------------------------------- older vaults

    override fun onMoveFromPrevious(vault: String) {
        if (blockedByDisagreement()) return
        // Only between the user's own vaults, into one agreeing endpoints confirmed.
        val cur = depositTarget() ?: return sheet(
            SheetUM.Result(ok = false, title = "Not yet", text = "Waiting for both data sources to confirm your Savings address. Try again in a moment.", url = null),
        )
        val old = ownVaults().firstOrNull { it.address.equals(vault, ignoreCase = true) } ?: return
        val tokens = SepoliaConfig.TOKENS.filter { old.available.of(it.address).signum() > 0 }
        val hold = holdText(old.delaySeconds)
        review(
            title = "Move ${moneyList(old.available)} to your new Savings",
            rows = listOf(
                "From" to "Previous Savings\n${groupedAddress(old.address)}",
                "To" to "Your new Savings\n${groupedAddress(cur.address)}",
                "Arrives" to "In $hold (the previous vault's protection)",
            ),
            note = "The previous vault's backup key can still cancel this while it waits.",
            calls = tokens.map { Call(old.address, SafeChainCalls.requestWithdrawal(it.address, old.available.of(it.address), cur.address)) },
            doneTitle = "Moving · arrives in $hold",
        )
    }

    override fun onReleaseFromPrevious(vault: String, id: String) {
        val old = snapshot?.vaults?.firstOrNull { it.address.equals(vault, ignoreCase = true) } ?: return
        release(old, id)
    }

    // ----------------------------------------------------------------- scanning

    override fun onScanRecovery() = scan(ScanTarget.Recovery)
    override fun onScanDestination() = scan(ScanTarget.Destination)
    override fun onScanSignature() = scan(ScanTarget.Signature)
    override fun onScanOtherWallet() = scan(ScanTarget.OtherWallet)
    override fun onScanOtherWalletSignature() = scan(ScanTarget.OtherSignature)

    private fun scan(target: ScanTarget) {
        scanTarget = target
        router.push(AppRoute.QrScanning(source = AppRoute.QrScanning.Source.SafeChain))
    }

    /** Plain addresses and EIP-681 payment requests (see [scannedAddress]); anything else is refused, said why. */
    private fun onScanned(raw: String) {
        fun address(fill: (String) -> Unit, error: (String) -> Unit) = when (val r = scannedAddress(raw)) {
            is ScannedAddress.Address -> fill(r.address)
            is ScannedAddress.Rejected -> error(r.reason)
        }
        when (scanTarget) {
            ScanTarget.Recovery -> address(::onSetupRecoveryChange, ::setupError)
            ScanTarget.Destination -> address(::onDestinationChange) { reason ->
                state.update {
                    when (val sh = it.sheet) {
                        is SheetUM.Send -> it.copy(sheet = sh.copy(error = reason))
                        is SheetUM.Withdraw -> it.copy(sheet = sh.copy(error = reason))
                        else -> it
                    }
                }
            }
            ScanTarget.Signature -> SIGNATURE.find(raw)?.value?.let(::onSetupSignatureChange)
            ScanTarget.OtherWallet -> address(::onOtherWalletAddressChange) { reason -> updateOther { o -> o.copy(error = reason) } }
            ScanTarget.OtherSignature -> SIGNATURE.find(raw)?.value?.let(::onOtherWalletSignatureChange)
            null -> Unit
        }
        scanTarget = null
    }

    override fun onBack() {
        if (state.value.page != null) onCloseAccount() else router.pop()
    }
    override fun onRefresh() { modelScope.launch { refresh() } }

    private fun sheet(s: SheetUM) = state.update { it.copy(sheet = s) }

    private fun consentLink(requestJson: String) =
        SepoliaConfig.CONSENT_PAGE + java.net.URLEncoder.encode(Base64.getEncoder().encodeToString(requestJson.toByteArray()), "UTF-8")

    private fun reason(e: Exception): String = when (e) {
        is SafeChainSignerError, is PhoneKeyError -> e.message.orEmpty()
        else -> e.message?.substringAfter("execution reverted")?.trim(':', ' ')?.ifBlank { null } ?: (e.message ?: "Unknown error")
    }

    private companion object {
        const val TIMEOUT_S = 15L
        const val TICK_MS = 1_000L
        /** As the web explorer on public chains: free endpoints rate-limit faster polling. */
        const val REFRESH_MS = 30_000L
        const val DEGRADED_READS_TO_WARN = 2
        const val HOUR = 3_600L
        const val MIN_HOURS = 24L
        const val MAX_HOURS = 720L
        const val SALT_BYTES = 32
        const val SIG_BYTES = 65
        const val FEE_DECIMALS = 8
        const val ETH_DECIMALS = 18
        const val SECONDS_PER_BLOCK = 12L
        const val POLICY_NEW_RECOVERY = 1
        const val HOLD_CUSTOM = "custom"
        const val MAX_ACTIVITY_ROWS = 30
        val ESCROW_KINDS = setOf(
            ActivityView.Kind.PaymentSent,
            ActivityView.Kind.PaymentReceived,
            ActivityView.Kind.PaymentCancelled,
            ActivityView.Kind.PaymentSettled,
        )

        const val DEMO_KEY_BYTES = 65
        const val DEMO_STEP_MS = 900L
        val DEMO_FEE: BigInteger = BigInteger.valueOf(1_000_000_000)
        val DEMO_GAS: BigInteger = BigInteger.valueOf(100_000)

        /** ETH the card gives the phone's backup key for emergency fees, and the level that triggers it. */
        val PHONE_GAS_TOP_UP: BigInteger = BigInteger.TEN.pow(15).multiply(BigInteger.TWO) // 0.002 ETH
        val PHONE_GAS_LOW: BigInteger = BigInteger.TEN.pow(15) // 0.001 ETH

        val ADDRESS = Regex("0x[0-9a-fA-F]{40}(?![0-9a-fA-F])")
        val TX_HASH = Regex("^0x[0-9a-fA-F]{64}$")
        const val PREFS = "safechain"
        val SIGNATURE = Regex("0x[0-9a-fA-F]{130}(?![0-9a-fA-F])")
        val MAX_UINT: BigInteger = BigInteger.ONE.shiftLeft(256) - BigInteger.ONE
        val UNIT: BigDecimal = BigDecimal.TEN.pow(ETH_DECIMALS)

        private val SYMBOLS = java.text.DecimalFormatSymbols(java.util.Locale.US)
        private val GROUPED = java.text.DecimalFormat("#,##0.##########", SYMBOLS)
        private val CENTS = java.text.DecimalFormat("#,##0.00", SYMBOLS)

        /** ETH amounts (18 decimals): thousands separators, no trailing zeros: 17,700 · 0.002 · 1,234.5. */
        fun fmt(v: BigInteger, decimals: Int = 4): String =
            GROUPED.format(BigDecimal(v).divide(UNIT).setScale(decimals, RoundingMode.DOWN).stripTrailingZeros())

        /** Native Sepolia ETH as a coin: it pays fees, has no dollar value here, and only Checking holds it. */
        val ETH_COIN = Token(AssetUM.ETH, "ETH", "Sepolia Ether", ETH_DECIMALS, usd = null)

        /** Every coin the screens show, in display order: the tokens, then ETH. */
        val COINS: List<Token> = SepoliaConfig.TOKENS + ETH_COIN

        /** A coin by address or key. An unknown token is labelled as such, never as one of ours. */
        fun coin(key: String): Token = COINS.firstOrNull { it.key == key.lowercase() }
            ?: Token(key, "unknown token", "Unknown token ${short(key)}", ETH_DECIMALS, usd = null)

        /** A token amount: priced tokens (USDC) with cents unless whole ("250", "12.50"), others like [fmt]. */
        fun fmt(v: BigInteger, t: Token): String {
            val x = BigDecimal(v).movePointLeft(t.decimals)
            return if (t.usd != null) CENTS.format(x.setScale(2, RoundingMode.DOWN)).removeSuffix(".00") else GROUPED.format(x.setScale(4, RoundingMode.DOWN).stripTrailingZeros())
        }

        /** "100 USDC". */
        fun money(v: BigInteger, t: Token) = "${fmt(v, t)} ${t.symbol}"

        fun usd(v: BigInteger, t: Token): BigDecimal? = t.usd?.multiply(BigDecimal(v).movePointLeft(t.decimals))

        fun usdText(x: BigDecimal) = "$" + CENTS.format(x.setScale(2, RoundingMode.DOWN))

        /**
         * Dollars for what has a price, then what has none: "$450.00" + "+ 18,000 PWTEST". With nothing priced
         * the headline is the amount itself. ETH is fee money and never counts.
         */
        fun balance(parts: List<Pair<Token, BigInteger>>): BalanceUM {
            val counted = parts.filter { (t, _) -> t.key != AssetUM.ETH }
            val dollars = counted.mapNotNull { (t, v) -> usd(v, t) }.fold(BigDecimal.ZERO, BigDecimal::add)
            val unpriced = counted.filter { (t, v) -> t.usd == null && v.signum() > 0 }.map { (t, v) -> money(v, t) }
            val anyPriced = counted.any { (t, _) -> t.usd != null }
            return if ((!anyPriced || dollars.signum() == 0) && unpriced.isNotEmpty()) {
                BalanceUM(unpriced.first(), unpriced.drop(1).takeIf { it.isNotEmpty() }?.joinToString(" + ", prefix = "+ "))
            } else {
                BalanceUM(usdText(dollars), unpriced.takeIf { it.isNotEmpty() }?.joinToString(" + ", prefix = "+ "))
            }
        }

        fun parseAmount(s: String, decimals: Int = ETH_DECIMALS): BigInteger? = runCatching {
            BigDecimal(s.trim().replace(",", "")).movePointRight(decimals).toBigIntegerExact().takeIf { it.signum() > 0 }
        }.getOrNull()

        fun parseAddress(s: String): String? = runCatching { parseUserAddress(s) }.getOrNull()
        fun parseSignature(s: String): ByteArray? = runCatching { Hex.decode(s.trim()) }.getOrNull()?.takeIf { it.size == SIG_BYTES }
        fun short(a: String?) = a?.let { "${it.take(6)}…${it.takeLast(4)}" }.orEmpty()
        fun time(ts: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ts * 1000))

        fun duration(seconds: Long): String {
            val h = seconds / HOUR
            val m = seconds % HOUR / 60
            val s = seconds % 60
            return if (h > 0) "${h}h ${m}m" else "${m}m ${s}s"
        }

        fun ago(seconds: Long): String = when {
            seconds < 60 -> "just now"
            seconds < HOUR -> "${seconds / 60} min ago"
            seconds < 86_400 -> "${seconds / HOUR} h ago"
            else -> "${seconds / 86_400} d ago"
        }
    }
}
