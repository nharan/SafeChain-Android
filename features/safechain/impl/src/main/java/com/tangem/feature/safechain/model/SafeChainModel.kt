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
import com.tangem.feature.safechain.chain.RpcHealth
import com.tangem.feature.safechain.chain.SafeChainCalls
import com.tangem.feature.safechain.chain.SepoliaConfig
import com.tangem.feature.safechain.chain.TxSender
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
import com.tangem.feature.safechain.ui.BackupChangeUM
import com.tangem.feature.safechain.ui.HealthUM
import com.tangem.feature.safechain.ui.PreviousVaultUM
import com.tangem.feature.safechain.ui.SafeChainActions
import com.tangem.feature.safechain.ui.SafeChainUM
import com.tangem.feature.safechain.ui.SetupUM
import com.tangem.feature.safechain.ui.SheetUM
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
    private var salt: ByteArray? = null
    private var busy = false

    /**
     * Debug builds only, and only for a wallet that isn't a Tangem card (an emulator has no NFC): a pretend
     * Sepolia ([DemoChain]) so every screen can be tried. A release or external build never enters it.
     */
    private var demo: DemoChain? = null

    /** The prepared transactions behind the Review sheet, and what comes after they're mined. */
    private var pending: Pending? = null

    private data class Pending(val txs: List<TxSender.Prepared>, val by: By, val from: String, val doneTitle: String, val then: (suspend () -> Unit)?)

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

    /** The vault the screen is about: the newest one this card spends from. */
    private fun current(s: AccountSnapshot? = snapshot): VaultView? {
        val me = account?.address ?: return null
        return s?.vaults?.lastOrNull { it.primary.equals(me, ignoreCase = true) } ?: s?.vaults?.lastOrNull()
    }

    private fun render() {
        val s = snapshot ?: return
        val me = account?.address ?: return
        val now = chainNow()
        val cur = current(s)
        state.update { ui ->
            ui.copy(
                total = money(s.tokenBalance + (cur?.balance ?: BigInteger.ZERO)),
                cardBalance = money(s.tokenBalance),
                gasBalance = "${fmt(s.ethBalance)} Sepolia ETH",
                phoneKey = phoneKey,
                phoneKeyGas = "${fmt(phoneKeyWei)} Sepolia ETH",
                phoneKeyLow = phoneKey != null && phoneKeyWei < PHONE_GAS_LOW,
                health = when (val h = health) {
                    RpcHealth.Agree, null -> HealthUM.Agree
                    is RpcHealth.Degraded -> HealthUM.Degraded(h.problems.joinToString(" · "))
                    is RpcHealth.Disagree -> HealthUM.Disagree(h.detail)
                },
                vault = cur?.toUM(me, now, s),
                previous = s.vaults
                    .filter { it !== cur && it.primary.equals(me, ignoreCase = true) }
                    .filter { it.balance.signum() > 0 || it.withdrawals.any { w -> w.state in 1..2 } }
                    .map { v ->
                        PreviousVaultUM(
                            address = v.address,
                            balance = money(v.balance),
                            available = money(v.available),
                            canMove = cur != null && !v.locked && v.available.signum() > 0,
                            moving = v.withdrawals.filter { it.state in 1..2 }.map { it.toUM(me, now, v.delaySeconds, s) },
                        )
                    },
            )
        }
    }

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
        val hours = delaySeconds / HOUR
        return VaultUM(
            address = address,
            balance = money(balance),
            available = money(available),
            partlyHeld = available < balance,
            hold = when {
                hours == HOURS_PER_DAY -> "24 hours"
                hours % HOURS_PER_DAY == 0L -> "${hours / HOURS_PER_DAY} days"
                else -> "$hours hours"
            },
            locked = locked,
            isPrimary = primary.equals(me, ignoreCase = true),
            recovery = recovery,
            backupIsThisPhone = backupIsThisPhone,
            haven = haven.takeIf { it != ZERO_ADDRESS },
            alerts = alerts,
            pending = withdrawals.filter { it.state in 1..2 }.map { it.toUM(me, now, delaySeconds, s) },
            activity = activity.map { it.toUM(me, s) },
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
        )
    }

    private fun WithdrawalView.toUM(me: String, now: Long, holdSeconds: Long, s: AccountSnapshot): WithdrawalUM {
        val span = (deadline - requestedAt).coerceAtLeast(holdSeconds).coerceAtLeast(1)
        return WithdrawalUM(
            id = id.toString(),
            amount = money(amount),
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
            disputed = disputed,
        )
    }

    private fun ActivityView.toUM(me: String, s: AccountSnapshot): ActivityUM {
        val plus = amount?.let { "+${fmt(it)}" }
        val minus = amount?.let { "−${fmt(it)}" }
        val who = when {
            by == null -> ""
            by.equals(me, ignoreCase = true) -> "by your card"
            by.equals(phoneKey, ignoreCase = true) -> "by this phone"
            else -> "by ${short(by)}"
        }
        data class Row(val title: String, val detail: String, val amount: String?, val icon: ActivityUM.Icon, val tone: ActivityUM.Tone)
        val row = when (kind) {
            ActivityView.Kind.Deposited -> Row(
                if (by.equals(me, ignoreCase = true)) "Moved from Checking" else "Received",
                if (by.equals(me, ignoreCase = true)) "" else "from ${label(by, me, s)}",
                plus, ActivityUM.Icon.In, ActivityUM.Tone.Good,
            )
            ActivityView.Kind.WithdrawalRequested -> Row("To ${label(address, me, s)}", "Payment #$withdrawalId started", minus, ActivityUM.Icon.Out, ActivityUM.Tone.Warning)
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
        }
        // Block times are about 12 s apart on Sepolia; good enough for "how long ago".
        val ago = (snapshotBlock - block).toLong().coerceAtLeast(0) * SECONDS_PER_BLOCK
        return ActivityUM(row.title, row.detail.trim(), ago(ago), row.amount, SepoliaConfig.EXPLORER_TX + txHash, row.icon, row.tone)
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
                pending = Pending(prepared, by, from, doneTitle, then)
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
                        "Next, move your money from your previous Savings: it's at the top of the screen."
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
        val hours = s.delayHours.toLong()
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

    override fun onOpenMove() = sheet(SheetUM.Move(savingsLocked = current()?.locked == true))

    override fun onMoveToSavings() = sheet(SheetUM.AddFunds(max = fmt(snapshot?.tokenBalance ?: BigInteger.ZERO)))

    override fun onMoveToChecking() {
        val v = current() ?: return
        sheet(SheetUM.Withdraw(max = fmt(v.available), toChecking = true))
    }

    override fun onOpenWithdraw() {
        val v = current() ?: return
        sheet(SheetUM.Withdraw(max = fmt(v.available)))
    }

    override fun onOpenReceive() {
        val v = current() ?: return
        sheet(SheetUM.Receive(v.address))
    }

    override fun onAmountChange(value: String) = state.update {
        when (val sh = it.sheet) {
            is SheetUM.AddFunds -> it.copy(sheet = sh.copy(amount = value, error = null))
            is SheetUM.Withdraw -> it.copy(sheet = sh.copy(amount = value, error = null))
            else -> it
        }
    }

    override fun onDestinationChange(value: String) = state.update {
        (it.sheet as? SheetUM.Withdraw)?.let { sh -> it.copy(sheet = sh.copy(to = value.trim(), error = null)) } ?: it
    }

    override fun onMax() = state.update {
        when (val sh = it.sheet) {
            is SheetUM.AddFunds -> it.copy(sheet = sh.copy(amount = sh.max, error = null))
            is SheetUM.Withdraw -> it.copy(sheet = sh.copy(amount = sh.max, error = null))
            else -> it
        }
    }

    override fun onContinue() {
        val v = current() ?: return
        when (val sh = state.value.sheet) {
            is SheetUM.AddFunds -> {
                val amount = parseAmount(sh.amount) ?: return sheet(sh.copy(error = "Enter an amount, for example 100."))
                if (amount > (snapshot?.tokenBalance ?: BigInteger.ZERO)) return sheet(sh.copy(error = "That's more than this card holds (${sh.max})."))
                review(
                    title = "Add ${fmt(amount)} ${SepoliaConfig.TOKEN_SYMBOL} to Savings",
                    rows = listOf("Into" to "Your Protected Savings\n${groupedAddress(v.address)}", "Amount" to "${fmt(amount)} ${SepoliaConfig.TOKEN_SYMBOL}"),
                    // A plain transfer counts as a deposit: one tap, no separate approval.
                    calls = listOf(Call(SepoliaConfig.TOKEN, SafeChainCalls.transfer(v.address, amount))),
                    doneTitle = "Added ${fmt(amount)} ${SepoliaConfig.TOKEN_SYMBOL}",
                )
            }
            is SheetUM.Withdraw -> {
                val amount = parseAmount(sh.amount) ?: return sheet(sh.copy(error = "Enter an amount, for example 100."))
                if (amount > v.available) return sheet(sh.copy(error = "Only ${sh.max} is available."))
                val to = if (sh.toChecking) {
                    account?.address ?: return
                } else {
                    parseAddress(sh.to) ?: return sheet(sh.copy(error = "Paste the destination's full address (0x…)."))
                }
                if (to.equals(v.address, ignoreCase = true)) return sheet(sh.copy(error = "That's your Savings itself."))
                val hours = v.delaySeconds / HOUR
                review(
                    title = if (sh.toChecking) "Move ${fmt(amount)} ${SepoliaConfig.TOKEN_SYMBOL} to Checking" else "Send ${fmt(amount)} ${SepoliaConfig.TOKEN_SYMBOL}",
                    rows = listOf(
                        "To" to if (sh.toChecking) "Your Checking (this card)\n${groupedAddress(to)}" else groupedAddress(to),
                        "Arrives" to "In ${hours}h. Until then you can cancel, and the money comes straight back.",
                    ),
                    note = "Check every character of the address. You can also check it from another device at safechain.rep.run.",
                    calls = listOf(Call(v.address, SafeChainCalls.requestWithdrawal(SepoliaConfig.TOKEN, amount, to))),
                    doneTitle = "Sending · arrives in ${hours}h",
                )
            }
            else -> Unit
        }
    }

    override fun onCancelWithdrawal(id: String) {
        val v = current() ?: return
        val w = v.withdrawals.firstOrNull { it.id.toString() == id } ?: return
        val amount = "${fmt(w.amount)} ${SepoliaConfig.TOKEN_SYMBOL}"
        review(
            title = "Cancel this payment",
            rows = listOf("Amount" to amount, "Was going to" to groupedAddress(w.to)),
            note = "The money comes straight back to your balance.",
            calls = listOf(Call(v.address, SafeChainCalls.cancelWithdrawal(w.id))),
            doneTitle = "Cancelled",
        ) {
            val balance = current()?.available?.let { "${fmt(it)} ${SepoliaConfig.TOKEN_SYMBOL}" }
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

    override fun onReleaseWithdrawal(id: String) {
        val v = current() ?: return
        release(v, id)
    }

    private fun release(v: VaultView, id: String) {
        val w = v.withdrawals.firstOrNull { it.id.toString() == id } ?: return
        review(
            title = "Deliver payment #$id",
            rows = listOf("Amount" to "${fmt(w.amount)} ${SepoliaConfig.TOKEN_SYMBOL}", "To" to groupedAddress(w.to)),
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
                    add("Stopped payments" to "${stopped.size} (${fmt(stopped.fold(BigInteger.ZERO) { a, w -> a + w.amount })} ${SepoliaConfig.TOKEN_SYMBOL}) come back to your balance")
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
            calls = listOf(Call(v.address, SafeChainCalls.requestHavenWithdrawal(SepoliaConfig.TOKEN, MAX_UINT, clear))),
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
        val cur = current() ?: return
        val old = snapshot?.vaults?.firstOrNull { it.address.equals(vault, ignoreCase = true) } ?: return
        val amount = old.available
        review(
            title = "Move ${fmt(amount)} ${SepoliaConfig.TOKEN_SYMBOL} to your new Savings",
            rows = listOf(
                "From" to "Previous Savings\n${groupedAddress(old.address)}",
                "To" to "Your new Savings\n${groupedAddress(cur.address)}",
                "Arrives" to "In ${old.delaySeconds / HOUR}h (the previous vault's protection)",
            ),
            note = "The previous vault's backup key can still cancel this while it waits.",
            calls = listOf(Call(old.address, SafeChainCalls.requestWithdrawal(SepoliaConfig.TOKEN, amount, cur.address))),
            doneTitle = "Moving · arrives in ${old.delaySeconds / HOUR}h",
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

    /** Accepts plain values and wallet QR formats such as `ethereum:0x…@11155111`. */
    private fun onScanned(raw: String) {
        when (scanTarget) {
            ScanTarget.Recovery -> ADDRESS.find(raw)?.value?.let(::onSetupRecoveryChange)
            ScanTarget.Destination -> ADDRESS.find(raw)?.value?.let(::onDestinationChange)
            ScanTarget.Signature -> SIGNATURE.find(raw)?.value?.let(::onSetupSignatureChange)
            ScanTarget.OtherWallet -> ADDRESS.find(raw)?.value?.let(::onOtherWalletAddressChange)
            ScanTarget.OtherSignature -> SIGNATURE.find(raw)?.value?.let(::onOtherWalletSignatureChange)
            null -> Unit
        }
        scanTarget = null
    }

    override fun onBack() = router.pop()
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
        const val REFRESH_MS = 15_000L
        const val HOUR = 3_600L
        const val MIN_HOURS = 24L
        const val MAX_HOURS = 720L
        const val SALT_BYTES = 32
        const val SIG_BYTES = 65
        const val FEE_DECIMALS = 8
        const val SECONDS_PER_BLOCK = 12L
        const val POLICY_NEW_RECOVERY = 1
        const val HOURS_PER_DAY = 24L
        const val DEMO_KEY_BYTES = 65
        const val DEMO_STEP_MS = 900L
        val DEMO_FEE: BigInteger = BigInteger.valueOf(1_000_000_000)
        val DEMO_GAS: BigInteger = BigInteger.valueOf(100_000)

        /** ETH the card gives the phone's backup key for emergency fees, and the level that triggers it. */
        val PHONE_GAS_TOP_UP: BigInteger = BigInteger.TEN.pow(15).multiply(BigInteger.TWO) // 0.002 ETH
        val PHONE_GAS_LOW: BigInteger = BigInteger.TEN.pow(15) // 0.001 ETH

        val ADDRESS = Regex("0x[0-9a-fA-F]{40}(?![0-9a-fA-F])")
        val SIGNATURE = Regex("0x[0-9a-fA-F]{130}(?![0-9a-fA-F])")
        val MAX_UINT: BigInteger = BigInteger.ONE.shiftLeft(256) - BigInteger.ONE
        val UNIT: BigDecimal = BigDecimal.TEN.pow(SepoliaConfig.TOKEN_DECIMALS)

        private val GROUPED = java.text.DecimalFormat("#,##0.##########", java.text.DecimalFormatSymbols(java.util.Locale.US))

        /** Thousands separators, no trailing zeros: 17,700 · 0.002 · 1,234.5. */
        fun fmt(v: BigInteger, decimals: Int = 4): String =
            GROUPED.format(BigDecimal(v).divide(UNIT).setScale(decimals, RoundingMode.DOWN).stripTrailingZeros())

        fun money(v: BigInteger) = "${fmt(v)} ${SepoliaConfig.TOKEN_SYMBOL}"

        fun parseAmount(s: String): BigInteger? = runCatching {
            BigDecimal(s.trim().replace(",", "")).multiply(UNIT).toBigIntegerExact().takeIf { it.signum() > 0 }
        }.getOrNull()

        fun parseAddress(s: String): String? = runCatching { checksumAddress(s.trim()) }.getOrNull()
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
