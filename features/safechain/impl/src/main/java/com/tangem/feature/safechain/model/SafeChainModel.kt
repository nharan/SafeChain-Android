package com.tangem.feature.safechain.model

import com.tangem.core.decompose.di.ModelScoped
import com.tangem.core.decompose.model.Model
import com.tangem.core.decompose.model.ParamsContainer
import com.tangem.core.decompose.navigation.Router
import com.tangem.feature.safechain.chain.AccountSnapshot
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
import com.tangem.feature.safechain.ui.ConfirmUM
import com.tangem.feature.safechain.ui.CreateVaultUM
import com.tangem.feature.safechain.ui.HealthUM
import com.tangem.feature.safechain.ui.SafeChainActions
import com.tangem.feature.safechain.ui.SafeChainUM
import com.tangem.feature.safechain.ui.VaultUM
import com.tangem.feature.safechain.ui.WithdrawalUM
import com.tangem.features.safechain.SafeChainComponent
import com.tangem.utils.coroutines.CoroutineDispatcherProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import java.math.BigDecimal
import java.math.BigInteger
import java.math.RoundingMode
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.inject.Inject

/**
 * SafeChain Protected Savings on Sepolia, with the Tangem card as the spending key.
 *
 * Every action is: simulate on the RPC endpoints (the contract's own checks run first) → show exactly
 * what will be signed, with full addresses → tap the card → broadcast → wait for the receipt → re-read.
 * Reads are cross-checked across two independent endpoints (block hash included).
 */
@ModelScoped
internal class SafeChainModel @Inject constructor(
    override val dispatchers: CoroutineDispatcherProvider,
    paramsContainer: ParamsContainer,
    private val router: Router,
    private val signer: CardEthSigner,
) : Model(), SafeChainActions {

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
    private var snapshotReadAtMs = 0L
    private var health: RpcHealth? = null
    private var salt: ByteArray? = null

    /** The transaction waiting for the user's OK, and what happens after it's mined. */
    private var pending: PendingTx? = null

    private data class PendingTx(val prepared: TxSender.Prepared, val confirm: ConfirmUM, val then: (suspend () -> Unit)?)

    val state: StateFlow<SafeChainUM>
        field = MutableStateFlow(SafeChainUM())

    init {
        modelScope.launch {
            busy("Reading your card's Ethereum key (you may be asked to tap the card)…")
            account = runCatching { signer.account(params.userWalletId) }
                .onFailure { e -> state.update { it.copy(loading = false, busy = null, error = e.message) } }
                .getOrNull() ?: return@launch
            state.update { it.copy(accountAddress = account!!.address, busy = null) }
            refresh()
        }
        modelScope.launch { // re-read every few seconds, and keep the countdowns ticking
            while (isActive) {
                delay(TICK_MS)
                if (System.currentTimeMillis() - snapshotReadAtMs > REFRESH_MS && state.value.busy == null) refresh()
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
            health = checked.health
            snapshotReadAtMs = System.currentTimeMillis()
            state.update { it.copy(loading = false, error = null) }
            render()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            state.update { it.copy(loading = false, error = "Couldn't read Sepolia: ${e.message}") }
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
                ethBalance = fmt(s.ethBalance) + " Sepolia ETH",
                tokenBalance = fmt(s.tokenBalance) + " " + SepoliaConfig.TOKEN_SYMBOL,
                health = when (val h = health) {
                    RpcHealth.Agree, null -> HealthUM.Agree
                    is RpcHealth.Degraded -> HealthUM.Degraded(h.problems.joinToString(" · "))
                    is RpcHealth.Disagree -> HealthUM.Disagree(h.detail)
                },
                vaults = s.vaults.map { v -> v.toUM(me, now, ui.vaults.firstOrNull { it.address == v.address }) },
            )
        }
    }

    private fun VaultView.toUM(me: String, now: Long, previous: VaultUM?): VaultUM {
        val isPrimary = primary.equals(me, ignoreCase = true)
        val warnings = buildList {
            if (pendingPrimary != ZERO_ADDRESS) {
                add("A spending-key replacement to ${groupedAddress(pendingPrimary)} is scheduled. If it wasn't you, your recovery key may be stolen: move everything to your safe haven now.")
            }
            if (vetoUntil > now) {
                add("Your spending key was replaced. Until ${time(vetoUntil)} you can still lock, cancel, or move everything to your safe haven.")
            }
            withdrawals.filter { it.state == 1 && !it.toHaven }.forEach {
                add("A withdrawal of ${fmt(it.amount)} ${SepoliaConfig.TOKEN_SYMBOL} to ${groupedAddress(it.to)} is pending. If you didn't request it, cancel it or lock Savings before ${time(it.deadline)}.")
            }
        }
        return VaultUM(
            address = address,
            balance = fmt(balance) + " " + SepoliaConfig.TOKEN_SYMBOL,
            available = fmt(available) + " " + SepoliaConfig.TOKEN_SYMBOL,
            locked = locked,
            isPrimary = isPrimary,
            isRecovery = recovery.equals(me, ignoreCase = true),
            recovery = recovery,
            haven = haven.takeIf { it != ZERO_ADDRESS },
            protection = "${delaySeconds / HOUR}h protection",
            warnings = warnings,
            disputed = disputed,
            withdrawals = withdrawals.filter { it.state in 1..2 }.map { w ->
                WithdrawalUM(
                    id = w.id.toString(),
                    amount = fmt(w.amount) + " " + SepoliaConfig.TOKEN_SYMBOL,
                    to = w.to,
                    toHaven = w.toHaven,
                    stateLabel = if (w.state == 1) "Pending — Cancellable" else "Final — Awaiting execution",
                    countdown = if (w.state == 1 && w.deadline > now) duration(w.deadline - now) + " left (est.)" else null,
                    // The contract decides; these only choose which buttons to offer.
                    canCancel = w.state == 1 && (!w.toHaven || w.initiator.equals(me, ignoreCase = true)),
                    canExecute = w.state == 2,
                    disputed = w.disputed,
                )
            },
            withdrawAmount = previous?.withdrawAmount.orEmpty(),
            withdrawTo = previous?.withdrawTo.orEmpty(),
            depositAmount = previous?.depositAmount.orEmpty(),
        )
    }

    // ----------------------------------------------------------------- actions

    /** Simulates [data] to [to], then asks the user to confirm what the card will sign. */
    private fun propose(title: String, lines: List<String>, to: String, data: ByteArray, then: (suspend () -> Unit)? = null) {
        val acct = account ?: return
        modelScope.launch {
            busy("Checking with the network…")
            try {
                val prepared = sender.prepare(acct.address, to, data)
                val confirm = ConfirmUM(
                    title = title,
                    lines = lines + "Contract: ${groupedAddress(to)}" + "Network: ${SepoliaConfig.NAME} (chain ${SepoliaConfig.CHAIN_ID})",
                    maxFee = "Max network fee: ${fmt(prepared.maxCostWei, 8)} Sepolia ETH",
                )
                pending = PendingTx(prepared, confirm, then)
                state.update { it.copy(busy = null, confirm = confirm) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(busy = null, message = "The contract would refuse this: ${e.message}") }
            }
        }
    }

    override fun onConfirm() {
        val p = pending ?: return
        val acct = account ?: return
        pending = null
        state.update { it.copy(confirm = null) }
        modelScope.launch {
            try {
                busy("Tap your card to sign…")
                val hash = p.prepared.tx.signingHash()
                val rsv = signer.sign(params.userWalletId, acct, hash)
                busy("Sent. Waiting for Sepolia to include it…")
                val raw = p.prepared.tx.signed(rsv.yParity, rsv.r, rsv.s)
                val txHash = sender.broadcastAndWait(raw, expectedFrom = acct.address)
                state.update { it.copy(busy = null, message = "Done: ${SepoliaConfig.EXPLORER_TX}$txHash") }
                refresh()
                p.then?.invoke()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                state.update { it.copy(busy = null, message = e.message ?: "Failed") }
                refresh()
            }
        }
    }

    override fun onDismissConfirm() {
        pending = null
        state.update { it.copy(confirm = null) }
    }

    override fun onStartCreate() {
        val acct = account ?: return
        val c = state.value.create
        val recovery = parseAddress(c.recovery) ?: return say("Enter the recovery key's full address.")
        if (recovery.equals(acct.address, ignoreCase = true)) return say("The recovery key must be a different key from this card.")
        val hours = c.delayHours.toLongOrNull()?.takeIf { it in MIN_HOURS..MAX_HOURS } ?: return say("Protection period: 24 to 720 hours.")
        val s = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }
        salt = s
        // The same ConsentRequest the web wallet's consent page reads (web/src/lib/consent.ts).
        val request = """{"kind":"create","primary":"${acct.address}","recovery":"$recovery","withdrawalDelay":"${hours * HOUR}","haven":"$ZERO_ADDRESS","salt":"${Hex.encode(s)}"}"""
        val link = SepoliaConfig.CONSENT_PAGE + java.net.URLEncoder.encode(Base64.getEncoder().encodeToString(request.toByteArray()), "UTF-8")
        state.update { it.copy(create = c.copy(recovery = recovery, consentLink = link)) }
    }

    override fun onCreateVault() {
        val acct = account ?: return
        val c = state.value.create
        val recovery = parseAddress(c.recovery) ?: return say("Enter the recovery key's full address.")
        val s = salt ?: return say("Start again: create the consent link first.")
        val sig = runCatching { Hex.decode(c.consentSignature.trim()) }.getOrNull()?.takeIf { it.size == SIG_BYTES }
            ?: return say("Paste the 65-byte signature (0x…) from the consent page.")
        val hours = c.delayHours.toLong()
        propose(
            title = "Create Protected Savings",
            lines = listOf(
                "Spending key: this card, ${groupedAddress(acct.address)}",
                "Recovery key: ${groupedAddress(recovery)}",
                "Protection period: ${hours}h. Withdrawals wait this long and can be cancelled until then.",
                "No safe haven yet. You can add one later with both keys.",
            ),
            to = SepoliaConfig.FACTORY,
            data = SafeChainCalls.createVault(acct.address, recovery, hours * HOUR, s, sig, ZERO_ADDRESS, ByteArray(0)),
        ) { state.update { it.copy(create = CreateVaultUM()) } }
    }

    override fun onDeposit(vault: String) {
        val v = vaultUM(vault) ?: return
        val amount = parseAmount(v.depositAmount) ?: return say("Enter an amount to add.")
        propose(
            title = "Allow the vault to take ${fmt(amount)} ${SepoliaConfig.TOKEN_SYMBOL} (1 of 2)",
            lines = listOf("Spender: your vault ${groupedAddress(vault)}", "After this, you'll confirm the deposit itself."),
            to = SepoliaConfig.TOKEN,
            data = SafeChainCalls.approve(vault, amount),
        ) {
            propose(
                title = "Add ${fmt(amount)} ${SepoliaConfig.TOKEN_SYMBOL} to Savings (2 of 2)",
                lines = listOf("Vault: ${groupedAddress(vault)}"),
                to = vault,
                data = SafeChainCalls.deposit(SepoliaConfig.TOKEN, amount),
            )
        }
    }

    override fun onRequestWithdrawal(vault: String) {
        val v = vaultUM(vault) ?: return
        val amount = parseAmount(v.withdrawAmount) ?: return say("Enter an amount to withdraw.")
        val to = parseAddress(v.withdrawTo) ?: return say("Enter the destination's full address.")
        propose(
            title = "Request a withdrawal of ${fmt(amount)} ${SepoliaConfig.TOKEN_SYMBOL}",
            lines = listOf(
                "To: ${groupedAddress(to)}",
                "Check every character of that address. It is final once the protection period ends.",
                "Until then you can cancel it, and so can your recovery key.",
            ),
            to = vault,
            data = SafeChainCalls.requestWithdrawal(SepoliaConfig.TOKEN, amount, to),
        )
    }

    override fun onCancelWithdrawal(vault: String, id: String) = propose(
        title = "Cancel withdrawal #$id",
        lines = listOf("The amount stays in your vault."),
        to = vault,
        data = SafeChainCalls.cancelWithdrawal(BigInteger(id)),
    )

    override fun onExecuteWithdrawal(vault: String, id: String) = propose(
        title = "Release withdrawal #$id",
        lines = listOf("It's final; this sends it to its destination."),
        to = vault,
        data = SafeChainCalls.executeWithdrawal(BigInteger(id)),
    )

    override fun onLock(vault: String) = propose(
        title = "Lock Savings",
        lines = listOf("Every pending withdrawal that isn't final yet is cancelled. Only the recovery key can unlock."),
        to = vault,
        data = SafeChainCalls.lock(),
    )

    override fun onMoveToHaven(vault: String) {
        val v = snapshot?.vaults?.firstOrNull { it.address == vault } ?: return
        if (v.haven == ZERO_ADDRESS) return say("This vault has no safe haven.")
        val clear = v.withdrawals.filter { it.state == 1 && !it.toHaven }.map { it.id }
        propose(
            title = "Move everything to your safe haven",
            lines = listOf("Safe haven: ${groupedAddress(v.haven)}", "Cancels ${clear.size} pending withdrawal(s) first. Only you can cancel this move."),
            to = vault,
            data = SafeChainCalls.requestHavenWithdrawal(SepoliaConfig.TOKEN, MAX_UINT, clear),
        )
    }

    // ----------------------------------------------------------------- form fields

    override fun onBack() = router.pop()
    override fun onRefresh() { modelScope.launch { refresh() } }
    override fun onDismissMessage() = state.update { it.copy(message = null) }
    override fun onRecoveryChange(value: String) = state.update { it.copy(create = it.create.copy(recovery = value, consentLink = null)) }
    override fun onDelayHoursChange(value: String) = state.update { it.copy(create = it.create.copy(delayHours = value, consentLink = null)) }
    override fun onConsentSignatureChange(value: String) = state.update { it.copy(create = it.create.copy(consentSignature = value)) }
    override fun onDepositAmountChange(vault: String, value: String) = editVault(vault) { it.copy(depositAmount = value) }
    override fun onWithdrawAmountChange(vault: String, value: String) = editVault(vault) { it.copy(withdrawAmount = value) }
    override fun onWithdrawToChange(vault: String, value: String) = editVault(vault) { it.copy(withdrawTo = value) }

    private fun editVault(vault: String, f: (VaultUM) -> VaultUM) =
        state.update { s -> s.copy(vaults = s.vaults.map { if (it.address == vault) f(it) else it }) }

    private fun vaultUM(vault: String) = state.value.vaults.firstOrNull { it.address == vault }
    private fun busy(text: String) = state.update { it.copy(busy = text, message = null) }
    private fun say(text: String) = state.update { it.copy(message = text) }

    private companion object {
        const val TIMEOUT_S = 15L
        const val TICK_MS = 1_000L
        const val REFRESH_MS = 15_000L
        const val HOUR = 3_600L
        const val MIN_HOURS = 24L
        const val MAX_HOURS = 720L
        const val SALT_BYTES = 32
        const val SIG_BYTES = 65
        val MAX_UINT: BigInteger = BigInteger.ONE.shiftLeft(256) - BigInteger.ONE
        val UNIT: BigDecimal = BigDecimal.TEN.pow(SepoliaConfig.TOKEN_DECIMALS)

        fun fmt(v: BigInteger, decimals: Int = 4): String =
            BigDecimal(v).divide(UNIT).setScale(decimals, RoundingMode.DOWN).stripTrailingZeros().toPlainString()

        fun parseAmount(s: String): BigInteger? = runCatching {
            BigDecimal(s.trim()).multiply(UNIT).toBigIntegerExact().takeIf { it.signum() > 0 }
        }.getOrNull()

        fun parseAddress(s: String): String? = runCatching { checksumAddress(s.trim()) }.getOrNull()

        fun time(ts: Long): String = java.text.DateFormat.getDateTimeInstance().format(java.util.Date(ts * 1000))

        fun duration(seconds: Long): String {
            val d = seconds / 86_400
            val h = seconds % 86_400 / HOUR
            val m = seconds % HOUR / 60
            return if (d > 0) "${d}d ${h}h ${m}m" else "${h}h ${m}m"
        }
    }
}
