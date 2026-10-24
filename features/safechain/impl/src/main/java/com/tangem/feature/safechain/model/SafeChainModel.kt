package com.tangem.feature.safechain.model

import arrow.core.getOrElse
import com.tangem.common.routing.AppRoute
import com.tangem.core.decompose.di.ModelScoped
import com.tangem.core.decompose.model.Model
import com.tangem.core.decompose.model.ParamsContainer
import com.tangem.core.decompose.navigation.Router
import com.tangem.feature.safechain.chain.AccountSnapshot
import com.tangem.feature.safechain.chain.ActivityView
import com.tangem.feature.safechain.chain.CrossCheckedReader
import com.tangem.feature.safechain.chain.EthRpc
import com.tangem.feature.safechain.chain.Hex
import com.tangem.feature.safechain.chain.OkHttpEthRpc
import com.tangem.feature.safechain.chain.RpcHealth
import com.tangem.feature.safechain.chain.SafeChainCalls
import com.tangem.feature.safechain.chain.SepoliaConfig
import com.tangem.feature.safechain.chain.TxSender
import com.tangem.feature.safechain.chain.VaultReader
import com.tangem.feature.safechain.chain.VaultView
import com.tangem.feature.safechain.chain.ZERO_ADDRESS
import com.tangem.feature.safechain.chain.checksumAddress
import com.tangem.feature.safechain.chain.groupedAddress
import com.tangem.feature.safechain.signing.CardEthSigner
import com.tangem.feature.safechain.signing.EthAccount
import com.tangem.feature.safechain.signing.SafeChainSignerError
import com.tangem.feature.safechain.ui.ActivityUM
import com.tangem.feature.safechain.ui.HealthUM
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
 * SafeChain Protected Savings on Sepolia, with the Tangem card as the spending key.
 *
 * Every signing action is one guided flow in a bottom sheet:
 * simulate on the endpoints (the contract's own checks run first) → Review, with full addresses → "Hold your
 * card" → "Sending to Sepolia" → Done (or the error, in the same sheet). Reads are cross-checked across two
 * independent endpoints, block hash included.
 */
@ModelScoped
@Suppress("LargeClass", "TooManyFunctions")
internal class SafeChainModel @Inject constructor(
    override val dispatchers: CoroutineDispatcherProvider,
    paramsContainer: ParamsContainer,
    private val router: Router,
    private val signer: CardEthSigner,
    private val listenToQrScanning: ListenToQrScanningUseCase,
) : Model(), SafeChainActions {

    /** Which field the next QR scan fills. */
    private enum class ScanTarget { Recovery, Destination, Signature }
    private var scanTarget: ScanTarget? = null

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
    private var snapshot: AccountSnapshot? = null
    private var snapshotBlock = BigInteger.ZERO
    private var snapshotReadAtMs = 0L
    private var health: RpcHealth? = null
    private var salt: ByteArray? = null
    private var busy = false

    /** The prepared transaction behind the Review sheet, and what comes after it is mined. */
    private var pending: Pending? = null

    private data class Pending(val prepared: TxSender.Prepared, val doneTitle: String, val then: (suspend () -> Unit)?)

    val state: StateFlow<SafeChainUM>
        field = MutableStateFlow(SafeChainUM())

    init {
        modelScope.launch {
            account = runCatching { signer.account(params.userWalletId) }
                .onFailure { e -> state.update { it.copy(loading = false, loadError = e.message) } }
                .getOrNull() ?: return@launch
            state.update { it.copy(accountAddress = account!!.address) }
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

    // ----------------------------------------------------------------- reading

    private suspend fun refresh() {
        val acct = account ?: return
        try {
            val checked = reader.read(AccountSnapshot::mergeUrgent) { rpc, block -> vaultReader.snapshot(rpc, block, acct.address) }
            snapshot = checked.value
            snapshotBlock = checked.block.number
            health = checked.health
            snapshotReadAtMs = System.currentTimeMillis()
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

    private fun render() {
        val s = snapshot ?: return
        val me = account?.address ?: return
        val now = chainNow()
        state.update { ui ->
            ui.copy(
                spendingBalance = "${fmt(s.tokenBalance)} ${SepoliaConfig.TOKEN_SYMBOL}",
                gasBalance = "${fmt(s.ethBalance)} Sepolia ETH",
                health = when (val h = health) {
                    RpcHealth.Agree, null -> HealthUM.Agree
                    is RpcHealth.Degraded -> HealthUM.Degraded(h.problems.joinToString(" · "))
                    is RpcHealth.Disagree -> HealthUM.Disagree(h.detail)
                },
                // The phone shows one vault: the newest one this card is a key of.
                vault = s.vaults.lastOrNull()?.toUM(me, now, s),
            )
        }
    }

    private fun VaultView.toUM(me: String, now: Long, s: AccountSnapshot): VaultUM {
        val alerts = buildList {
            if (pendingPrimary != ZERO_ADDRESS) {
                add("A spending-key replacement to ${groupedAddress(pendingPrimary)} is scheduled. If it wasn't you, your recovery key may be stolen: move everything to your safe haven now.")
            }
            if (vetoUntil > now) {
                add("Your spending key was replaced. Until ${time(vetoUntil)} you can still lock, cancel, or move everything to your safe haven.")
            }
            withdrawals.filter { it.state == 1 && !it.toHaven }.forEach {
                add("${fmt(it.amount)} ${SepoliaConfig.TOKEN_SYMBOL} is on its way out to ${short(it.to)}. If you didn't request it, cancel it before ${time(it.deadline)}.")
            }
        }
        return VaultUM(
            address = address,
            balance = "${fmt(balance)} ${SepoliaConfig.TOKEN_SYMBOL}",
            available = "${fmt(available)} ${SepoliaConfig.TOKEN_SYMBOL}",
            protection = "Protected · ${delaySeconds / HOUR}h",
            locked = locked,
            isPrimary = primary.equals(me, ignoreCase = true),
            isRecovery = recovery.equals(me, ignoreCase = true),
            recovery = recovery,
            haven = haven.takeIf { it != ZERO_ADDRESS },
            alerts = alerts,
            pending = withdrawals.filter { it.state in 1..2 }.map { w ->
                WithdrawalUM(
                    id = w.id.toString(),
                    amount = "${fmt(w.amount)} ${SepoliaConfig.TOKEN_SYMBOL}",
                    to = w.to,
                    toHaven = w.toHaven,
                    isFinal = w.state == 2,
                    countdown = if (w.state == 1 && w.deadline > now) duration(w.deadline - now) else null,
                    deadline = time(w.deadline),
                    // The contract decides; these only choose which buttons to offer.
                    canCancel = w.state == 1 && (!w.toHaven || w.initiator.equals(me, ignoreCase = true)),
                    canRelease = w.state == 2,
                    disputed = w.disputed,
                )
            },
            activity = activity.map { it.toUM(me, s) },
            disputed = disputed,
        )
    }

    private fun ActivityView.toUM(me: String, s: AccountSnapshot): ActivityUM {
        val amt = amount?.let { "${fmt(it)} ${SepoliaConfig.TOKEN_SYMBOL}" }.orEmpty()
        val byYou = by?.equals(me, ignoreCase = true) == true
        val who = if (byYou) "by this card" else by?.let { "by ${short(it)}" }.orEmpty()
        val (title, detail, tone) = when (kind) {
            ActivityView.Kind.Deposited -> Triple("Added $amt", who, ActivityUM.Tone.Good)
            ActivityView.Kind.WithdrawalRequested -> Triple("Withdrawal #$withdrawalId requested · $amt", "to ${short(address)} $who", ActivityUM.Tone.Warning)
            ActivityView.Kind.HavenWithdrawalRequested -> Triple("Move #$withdrawalId to safe haven", "to ${short(address)} $who", ActivityUM.Tone.Warning)
            ActivityView.Kind.WithdrawalCancelled -> Triple("Withdrawal #$withdrawalId cancelled", who, ActivityUM.Tone.Good)
            ActivityView.Kind.WithdrawalExecuted -> Triple("Withdrawal #$withdrawalId released", who, ActivityUM.Tone.Neutral)
            ActivityView.Kind.VoidedReleased -> Triple("Withdrawal #$withdrawalId voided by a lock", "", ActivityUM.Tone.Good)
            ActivityView.Kind.Locked -> Triple("Savings locked", who, ActivityUM.Tone.Danger)
            ActivityView.Kind.Unlocked -> Triple("Savings unlocked", who, ActivityUM.Tone.Neutral)
            ActivityView.Kind.ReplacementScheduled -> Triple("Spending-key replacement scheduled", "to ${short(address)}", ActivityUM.Tone.Danger)
            ActivityView.Kind.ReplacementCancelled -> Triple("Spending-key replacement cancelled", "", ActivityUM.Tone.Good)
            ActivityView.Kind.PrimaryReplaced -> Triple("Spending key replaced", "now ${short(address)}", ActivityUM.Tone.Danger)
            ActivityView.Kind.RecoveryReplaced -> Triple("Recovery key replaced", "now ${short(address)}", ActivityUM.Tone.Danger)
            ActivityView.Kind.HavenReplaced -> Triple("Safe haven changed", "now ${short(address)}", ActivityUM.Tone.Warning)
            ActivityView.Kind.DelayChanged -> Triple("Protection period changed", "", ActivityUM.Tone.Warning)
            ActivityView.Kind.SecurityChange -> Triple("Security change", "", ActivityUM.Tone.Warning)
        }
        // Block times are about 12 s apart on Sepolia; good enough for "how long ago".
        val ago = (snapshotBlock - block).toLong().coerceAtLeast(0) * SECONDS_PER_BLOCK
        return ActivityUM(title, detail.trim(), ago(ago), SepoliaConfig.EXPLORER_TX + txHash, tone)
    }

    // ----------------------------------------------------------------- the guided flow

    /** Simulates [data] to [to], then shows the Review sheet with exactly what the card will sign. */
    @Suppress("LongParameterList")
    private fun review(
        title: String,
        rows: List<Pair<String, String>>,
        to: String,
        data: ByteArray,
        doneTitle: String,
        step: String? = null,
        note: String? = null,
        danger: Boolean = false,
        then: (suspend () -> Unit)? = null,
    ) {
        val acct = account ?: return
        modelScope.launch {
            busy = true
            sheet(SheetUM.Working(title, "Checking with Sepolia…", holdCard = false))
            try {
                val prepared = sender.prepare(acct.address, to, data)
                pending = Pending(prepared, doneTitle, then)
                sheet(
                    SheetUM.Review(
                        title = title,
                        step = step,
                        rows = rows + ("Contract" to groupedAddress(to)) + ("Network" to "${SepoliaConfig.NAME} · chain ${SepoliaConfig.CHAIN_ID}"),
                        note = note,
                        fee = "Max network fee ${fmt(prepared.maxCostWei, FEE_DECIMALS)} Sepolia ETH",
                        danger = danger,
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
                sheet(SheetUM.Working(title, "Hold your card to the back of the phone until it's done.", holdCard = true))
                val rsv = signer.sign(params.userWalletId, acct, p.prepared.tx.signingHash())
                sheet(SheetUM.Working(title, "Signed. Sending to Sepolia and waiting for it to be included…", holdCard = false))
                val raw = p.prepared.tx.signed(rsv.yParity, rsv.r, rsv.s)
                val txHash = sender.broadcastAndWait(raw, expectedFrom = acct.address)
                refresh()
                if (p.then != null) {
                    p.then.invoke()
                } else {
                    sheet(SheetUM.Result(ok = true, title = p.doneTitle, text = "Confirmed on Sepolia.", url = SepoliaConfig.EXPLORER_TX + txHash))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SafeChainSignerError.Cancelled) {
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

    // ----------------------------------------------------------------- setup

    override fun onSetupMakeLink() {
        val acct = account ?: return
        val s = state.value.setup
        val recovery = parseAddress(s.recovery) ?: return setupError("That isn't a valid address. Paste the recovery key's full address.")
        if (recovery.equals(acct.address, ignoreCase = true)) {
            return setupError("The recovery key must be a different key from this card, on another device.")
        }
        val hours = s.delayHours.toLongOrNull()?.takeIf { it in MIN_HOURS..MAX_HOURS }
            ?: return setupError("Protection period: 24 to 720 hours.")
        val newSalt = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        salt = newSalt
        // The same ConsentRequest the web wallet's consent page reads (SafeChain web/src/lib/consent.ts).
        val request = """{"kind":"create","primary":"${acct.address}","recovery":"$recovery","withdrawalDelay":"${hours * HOUR}","haven":"$ZERO_ADDRESS","salt":"${Hex.encode(newSalt)}"}"""
        val link = SepoliaConfig.CONSENT_PAGE + java.net.URLEncoder.encode(Base64.getEncoder().encodeToString(request.toByteArray()), "UTF-8")
        state.update { it.copy(setup = s.copy(recovery = recovery, consentLink = link, signature = "", error = null)) }
    }

    override fun onSetupCreate() {
        val acct = account ?: return
        val s = state.value.setup
        val recovery = parseAddress(s.recovery) ?: return setupError("That isn't a valid address.")
        val currentSalt = salt ?: return setupError("Make the consent link first (step 2).")
        val sig = runCatching { Hex.decode(s.signature.trim()) }.getOrNull()?.takeIf { it.size == SIG_BYTES }
            ?: return setupError("Paste the recovery key's signature from the consent page: 0x followed by 130 characters.")
        val hours = s.delayHours.toLong()
        setupError(null)
        review(
            title = "Create Protected Savings",
            rows = listOf(
                "Spending key" to "This card\n${groupedAddress(acct.address)}",
                "Recovery key" to groupedAddress(recovery),
                "Protection" to "${hours}h: withdrawals wait this long and can be cancelled until then",
                "Safe haven" to "None yet (add one later with both keys)",
            ),
            to = SepoliaConfig.FACTORY,
            data = SafeChainCalls.createVault(acct.address, recovery, hours * HOUR, currentSalt, sig, ZERO_ADDRESS, ByteArray(0)),
            doneTitle = "Protected Savings created",
        ) {
            state.update { it.copy(setup = SetupUM()) }
            sheet(SheetUM.Result(ok = true, title = "Protected Savings created", text = "Your card is the spending key; the recovery key can cancel and lock.", url = null))
        }
    }

    override fun onSetupRecoveryChange(value: String) = state.update { it.copy(setup = it.setup.copy(recovery = value, consentLink = null, error = null)) }
    override fun onSetupDelayChange(value: String) = state.update { it.copy(setup = it.setup.copy(delayHours = value, consentLink = null, error = null)) }
    override fun onSetupSignatureChange(value: String) = state.update { it.copy(setup = it.setup.copy(signature = value.trim(), error = null)) }
    private fun setupError(text: String?) = state.update { it.copy(setup = it.setup.copy(error = text)) }

    // ----------------------------------------------------------------- vault actions

    override fun onOpenAddFunds() = sheet(SheetUM.AddFunds(max = fmt(snapshot?.tokenBalance ?: BigInteger.ZERO)))

    override fun onOpenWithdraw() {
        val v = snapshot?.vaults?.lastOrNull() ?: return
        sheet(SheetUM.Withdraw(max = fmt(v.available)))
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
        val v = snapshot?.vaults?.lastOrNull() ?: return
        when (val sh = state.value.sheet) {
            is SheetUM.AddFunds -> {
                val amount = parseAmount(sh.amount) ?: return sheet(sh.copy(error = "Enter an amount, for example 100."))
                if (amount > (snapshot?.tokenBalance ?: BigInteger.ZERO)) return sheet(sh.copy(error = "That's more than this card holds (${sh.max})."))
                review(
                    title = "Allow your vault to take ${fmt(amount)} ${SepoliaConfig.TOKEN_SYMBOL}",
                    step = "Step 1 of 2",
                    rows = listOf("Allow" to "Your Protected Savings\n${groupedAddress(v.address)}", "Amount" to "${fmt(amount)} ${SepoliaConfig.TOKEN_SYMBOL}"),
                    note = "Next you'll confirm the deposit itself.",
                    to = SepoliaConfig.TOKEN,
                    data = SafeChainCalls.approve(v.address, amount),
                    doneTitle = "Allowed",
                ) {
                    review(
                        title = "Add ${fmt(amount)} ${SepoliaConfig.TOKEN_SYMBOL} to Savings",
                        step = "Step 2 of 2",
                        rows = listOf("Into" to "Your Protected Savings\n${groupedAddress(v.address)}", "Amount" to "${fmt(amount)} ${SepoliaConfig.TOKEN_SYMBOL}"),
                        note = null,
                        to = v.address,
                        data = SafeChainCalls.deposit(SepoliaConfig.TOKEN, amount),
                        doneTitle = "Added ${fmt(amount)} ${SepoliaConfig.TOKEN_SYMBOL} to Savings",
                    )
                }
            }
            is SheetUM.Withdraw -> {
                val amount = parseAmount(sh.amount) ?: return sheet(sh.copy(error = "Enter an amount, for example 100."))
                if (amount > v.available) return sheet(sh.copy(error = "Only ${sh.max} is available."))
                val to = parseAddress(sh.to) ?: return sheet(sh.copy(error = "Paste the destination's full address (0x…)."))
                if (to.equals(v.address, ignoreCase = true)) return sheet(sh.copy(error = "That's the vault itself."))
                review(
                    title = "Withdraw ${fmt(amount)} ${SepoliaConfig.TOKEN_SYMBOL}",
                    rows = listOf(
                        "To" to groupedAddress(to),
                        "Arrives" to "After ${v.delaySeconds / HOUR}h, unless you cancel",
                        "From" to "Your Protected Savings",
                    ),
                    note = "Check every character of the address. You or your recovery key can cancel until the protection period ends; after that it's final.",
                    to = v.address,
                    data = SafeChainCalls.requestWithdrawal(SepoliaConfig.TOKEN, amount, to),
                    doneTitle = "Withdrawal requested",
                )
            }
            else -> Unit
        }
    }

    override fun onCancelWithdrawal(id: String) {
        val v = snapshot?.vaults?.lastOrNull() ?: return
        val w = v.withdrawals.firstOrNull { it.id.toString() == id } ?: return
        review(
            title = "Cancel withdrawal #$id",
            rows = listOf("Amount" to "${fmt(w.amount)} ${SepoliaConfig.TOKEN_SYMBOL}", "Was going to" to groupedAddress(w.to)),
            note = "The money stays in your Protected Savings.",
            to = v.address,
            data = SafeChainCalls.cancelWithdrawal(w.id),
            doneTitle = "Withdrawal #$id cancelled",
        )
    }

    override fun onReleaseWithdrawal(id: String) {
        val v = snapshot?.vaults?.lastOrNull() ?: return
        val w = v.withdrawals.firstOrNull { it.id.toString() == id } ?: return
        review(
            title = "Release withdrawal #$id",
            rows = listOf("Amount" to "${fmt(w.amount)} ${SepoliaConfig.TOKEN_SYMBOL}", "To" to groupedAddress(w.to)),
            note = "It's final; this sends it to its destination.",
            to = v.address,
            data = SafeChainCalls.executeWithdrawal(w.id),
            doneTitle = "Withdrawal #$id released",
        )
    }

    override fun onLock() {
        val v = snapshot?.vaults?.lastOrNull() ?: return
        review(
            title = "Lock Savings",
            rows = listOf("Vault" to groupedAddress(v.address)),
            note = "Every withdrawal that isn't final yet is voided, and no new ones can be requested. Only the recovery key can unlock.",
            to = v.address,
            data = SafeChainCalls.lock(),
            doneTitle = "Savings locked",
            danger = true,
        )
    }

    override fun onMoveToHaven() {
        val v = snapshot?.vaults?.lastOrNull() ?: return
        if (v.haven == ZERO_ADDRESS) return
        val clear = v.withdrawals.filter { it.state == 1 && !it.toHaven }.map { it.id }
        review(
            title = "Move everything to your safe haven",
            rows = listOf("Safe haven" to groupedAddress(v.haven), "Cancels first" to "${clear.size} pending withdrawal(s)"),
            note = "Only you can cancel this move. It arrives after the protection period.",
            to = v.address,
            data = SafeChainCalls.requestHavenWithdrawal(SepoliaConfig.TOKEN, MAX_UINT, clear),
            doneTitle = "Moving everything to your safe haven",
            danger = true,
        )
    }

    // ----------------------------------------------------------------- scanning

    override fun onScanRecovery() = scan(ScanTarget.Recovery)
    override fun onScanDestination() = scan(ScanTarget.Destination)
    override fun onScanSignature() = scan(ScanTarget.Signature)

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
            null -> Unit
        }
        scanTarget = null
    }

    override fun onBack() = router.pop()
    override fun onRefresh() { modelScope.launch { refresh() } }

    private fun sheet(s: SheetUM) = state.update { it.copy(sheet = s) }

    private fun reason(e: Exception): String = when (e) {
        is SafeChainSignerError -> e.message.orEmpty()
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
        val ADDRESS = Regex("0x[0-9a-fA-F]{40}(?![0-9a-fA-F])")
        val SIGNATURE = Regex("0x[0-9a-fA-F]{130}(?![0-9a-fA-F])")
        val MAX_UINT: BigInteger = BigInteger.ONE.shiftLeft(256) - BigInteger.ONE
        val UNIT: BigDecimal = BigDecimal.TEN.pow(SepoliaConfig.TOKEN_DECIMALS)

        fun fmt(v: BigInteger, decimals: Int = 4): String =
            BigDecimal(v).divide(UNIT).setScale(decimals, RoundingMode.DOWN).stripTrailingZeros().toPlainString()

        fun parseAmount(s: String): BigInteger? = runCatching {
            BigDecimal(s.trim().replace(",", "")).multiply(UNIT).toBigIntegerExact().takeIf { it.signum() > 0 }
        }.getOrNull()

        fun parseAddress(s: String): String? = runCatching { checksumAddress(s.trim()) }.getOrNull()
        fun short(a: String?) = a?.let { "${it.take(6)}…${it.takeLast(4)}" }.orEmpty()
        fun time(ts: Long): String = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(ts * 1000))

        fun duration(seconds: Long): String {
            val h = seconds / HOUR
            val m = seconds % HOUR / 60
            val s = seconds % 60
            return if (h > 0) "${h}h ${m}m left" else "${m}m ${s}s left"
        }

        fun ago(seconds: Long): String = when {
            seconds < 60 -> "just now"
            seconds < HOUR -> "${seconds / 60} min ago"
            seconds < 86_400 -> "${seconds / HOUR} h ago"
            else -> "${seconds / 86_400} d ago"
        }
    }
}
