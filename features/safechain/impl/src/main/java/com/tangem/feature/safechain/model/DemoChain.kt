package com.tangem.feature.safechain.model

import com.tangem.feature.safechain.chain.AccountSnapshot
import com.tangem.feature.safechain.chain.Abi
import com.tangem.feature.safechain.chain.ActivityView
import com.tangem.feature.safechain.chain.Hex
import com.tangem.feature.safechain.chain.PaymentView
import com.tangem.feature.safechain.chain.PolicyView
import com.tangem.feature.safechain.chain.SafeChainCalls
import com.tangem.feature.safechain.chain.SepoliaConfig
import com.tangem.feature.safechain.chain.VaultView
import com.tangem.feature.safechain.chain.WithdrawalView
import com.tangem.feature.safechain.chain.ZERO_ADDRESS
import com.tangem.feature.safechain.chain.checksumAddress
import java.math.BigInteger

/**
 * DEBUG BUILDS ONLY: a pretend Sepolia, so every SafeChain screen and sheet can be tried on an emulator, which
 * has no NFC for the card. Nothing here touches the network or a key. It follows the vault contract's rules
 * for the calls this app makes (requests, cancels, locks, releases, deposits, backup changes), closely enough
 * to see each state; the real contracts remain the authority.
 */
internal class DemoChain(private val now: () -> Long = { System.currentTimeMillis() / 1000 }) {

    class Revert(reason: String) : Exception("execution reverted: $reason")

    private class W(
        val id: BigInteger,
        val to: String,
        val amount: BigInteger,
        val requestedAt: Long,
        val deadline: Long,
        val initiator: String,
        val epoch: Int,
        var status: Int = PENDING, // PENDING, EXECUTED, CANCELLED
    )

    private class V(val address: String, var primary: String, var recovery: String, val delay: Long) {
        var balance: BigInteger = BigInteger.ZERO
        var locked = false
        var epoch = 0
        val lockedAt = mutableMapOf<Int, Long>()
        val withdrawals = mutableListOf<W>()
        val activity = mutableListOf<ActivityView>()
        var policyNonce: BigInteger = BigInteger.ZERO
        var policy: PolicyView? = null
    }

    /** A protected payment in the escrow (status: PENDING, EXECUTED = settled, CANCELLED). */
    private class P(
        val id: BigInteger,
        val sender: String,
        val recipient: String,
        val amount: BigInteger,
        val createdAt: Long,
        val deadline: Long,
        var status: Int = PENDING,
    )

    private var block = BigInteger.valueOf(START_BLOCK)
    private var cardTokens = units(17_700)
    private var cardWei = BigInteger.TEN.pow(18).multiply(BigInteger.valueOf(4))
    private val wei = mutableMapOf(PHONE.lowercase() to BigInteger.TEN.pow(15).multiply(BigInteger.TWO))
    private val vaults = mutableListOf<V>()
    private var escrowAllowance = BigInteger.ZERO
    private val payments = mutableListOf<P>()
    private val checking = mutableListOf<ActivityView>()

    init {
        // Someone is sending this card a protected payment: an incoming row in Upcoming.
        val t = now() - 2 * HOUR
        payments += P(BigInteger.ONE, FRIEND, CARD, units(50), t, t + 6 * HOUR)
        checking += event(ActivityView.Kind.PaymentReceived, id = BigInteger.ONE, amount = units(50), address = FRIEND, ago = 2 * HOUR)
        // A previous vault backed by another wallet, still holding money: the "move it here" banner.
        vaults += V(OLD_VAULT, CARD, OTHER_WALLET, DAY).apply {
            balance = units(2_000)
            activity += event(ActivityView.Kind.Deposited, amount = units(2_000), by = CARD, ago = 3 * DAY)
        }
        // The current vault, backed by this phone, with one payment on its way: the cancel and lock flows.
        vaults += V(VAULT, CARD, PHONE, DAY).apply {
            balance = units(300)
            activity += event(ActivityView.Kind.Deposited, amount = units(300), by = CARD, ago = 2 * HOUR)
            val t = now() - HOUR
            withdrawals += W(BigInteger.ONE, RANDOM_ADDRESS, units(100), t, t + DAY, CARD, epoch)
            activity += event(ActivityView.Kind.WithdrawalRequested, id = BigInteger.ONE, amount = units(100), address = RANDOM_ADDRESS, by = CARD, ago = HOUR)
        }
    }

    fun ethBalance(address: String): BigInteger = wei[address.lowercase()] ?: BigInteger.ZERO

    fun snapshot(account: String): AccountSnapshot = AccountSnapshot(
        address = account,
        ethBalance = cardWei,
        tokenBalance = cardTokens,
        vaults = vaults.map(::view),
        chainTime = now(),
        payments = payments.sortedByDescending { it.id }.map { p ->
            val state = when {
                p.status == EXECUTED -> 3
                p.status == CANCELLED -> 4
                now() < p.deadline -> 1
                else -> 2
            }
            PaymentView(p.id, p.sender, p.recipient, p.amount, p.createdAt, p.deadline, state)
        },
        checkingActivity = checking.sortedByDescending { it.block },
        escrowAllowance = escrowAllowance,
    )

    val head: BigInteger get() = block

    /** Applies one call as the contract would, or throws [Revert] with the contract's reason. */
    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun apply(from: String, to: String, data: ByteArray, value: BigInteger) {
        block += BigInteger.ONE
        val args = Abi.words(data.copyOfRange(SELECTOR, data.size.coerceAtLeast(SELECTOR)))
        val selector = if (data.size >= SELECTOR) Hex.encode(data.copyOf(SELECTOR)) else ""
        fun uintArg(i: Int) = Abi.uint(args[i])
        fun addrArg(i: Int) = Abi.address(args[i])

        if (value.signum() > 0) {
            if (cardWei < value) throw Revert("insufficient funds")
            cardWei -= value
            wei[to.lowercase()] = ethBalance(to) + value
            if (data.isEmpty()) return
        }
        if (to.equals(SepoliaConfig.TOKEN, ignoreCase = true)) {
            if (selector == sel(SafeChainCalls.approve(ZERO_ADDRESS, BigInteger.ZERO))) {
                if (addrArg(0).equals(SepoliaConfig.ESCROW, ignoreCase = true)) escrowAllowance = uintArg(1)
                return
            }
            require(selector == sel(SafeChainCalls.transfer(ZERO_ADDRESS, BigInteger.ZERO))) { "demo: unsupported token call" }
            val dest = addrArg(0)
            val amount = uintArg(1)
            if (amount > cardTokens) throw Revert("ERC20: transfer amount exceeds balance")
            cardTokens -= amount
            val v = vault(dest)
            if (v != null) {
                v.balance += amount
                v.activity += event(ActivityView.Kind.Deposited, amount = amount, by = from)
            } else {
                checking += event(ActivityView.Kind.CheckingSent, amount = amount, address = dest, by = from)
            }
            return
        }
        if (to.equals(SepoliaConfig.ESCROW, ignoreCase = true)) {
            escrow(from, selector, ::uintArg, ::addrArg)
            return
        }
        if (to.equals(SepoliaConfig.FACTORY, ignoreCase = true)) {
            val address = checksumAddress("0x" + "5afe".repeat(9) + "%04x".format(vaults.size))
            vaults += V(address, addrArg(0), addrArg(1), uintArg(2).toLong())
            return
        }
        val v = vault(to) ?: throw Revert("not a vault")
        val isPrimary = from.equals(v.primary, ignoreCase = true)
        val isRecovery = from.equals(v.recovery, ignoreCase = true)
        when (selector) {
            sel(SafeChainCalls.requestWithdrawal(ZERO_ADDRESS, BigInteger.ZERO, ZERO_ADDRESS)) -> {
                if (!isPrimary) throw Revert("NotPrimary")
                if (v.locked) throw Revert("VaultLocked")
                val amount = uintArg(1)
                if (amount > available(v)) throw Revert("InsufficientAvailable")
                val id = BigInteger.valueOf(v.withdrawals.size + 1L)
                val dest = addrArg(2)
                v.withdrawals += W(id, dest, amount, now(), now() + v.delay, from, v.epoch)
                v.activity += event(ActivityView.Kind.WithdrawalRequested, id = id, amount = amount, address = dest, by = from)
            }
            sel(SafeChainCalls.cancelWithdrawal(BigInteger.ZERO)) -> {
                if (!isPrimary && !isRecovery) throw Revert("NotSigner")
                val w = withdrawal(v, uintArg(0))
                if (w.status != PENDING) throw Revert("NotPending")
                if (now() >= w.deadline) throw Revert("DeadlinePassed")
                w.status = CANCELLED
                v.activity += event(ActivityView.Kind.WithdrawalCancelled, id = w.id, by = from)
            }
            sel(SafeChainCalls.executeWithdrawal(BigInteger.ZERO)) -> {
                val w = withdrawal(v, uintArg(0))
                if (w.status != PENDING) throw Revert("NotPending")
                if (now() < w.deadline) throw Revert("DeadlineNotReached")
                if (isVoid(v, w)) throw Revert("WithdrawalIsVoid")
                w.status = EXECUTED
                v.balance -= w.amount
                vault(w.to)?.let { it.balance += w.amount }
                v.activity += event(ActivityView.Kind.WithdrawalExecuted, id = w.id, by = from)
            }
            sel(SafeChainCalls.lock()) -> {
                if (!isPrimary && !isRecovery) throw Revert("NotSigner")
                if (v.locked) throw Revert("VaultLocked")
                v.lockedAt[v.epoch] = now()
                v.epoch++
                v.locked = true
                v.activity += event(ActivityView.Kind.Locked, by = from)
            }
            sel(SafeChainCalls.unlock()) -> {
                if (!isRecovery) throw Revert("NotRecovery")
                if (!v.locked) throw Revert("NotLocked")
                v.locked = false
                v.activity += event(ActivityView.Kind.Unlocked, by = from)
            }
            sel(SafeChainCalls.releaseVoided(BigInteger.ZERO)) -> {
                val w = withdrawal(v, uintArg(0))
                if (w.status != PENDING) throw Revert("NotPending")
                if (!isVoid(v, w)) throw Revert("WithdrawalNotVoid")
                w.status = CANCELLED
                v.activity += event(ActivityView.Kind.VoidedReleased, id = w.id)
            }
            sel(SafeChainCalls.proposeRecoveryChange(ZERO_ADDRESS, ByteArray(0))) -> {
                if (!isPrimary && !isRecovery) throw Revert("NotSigner")
                if (v.policy != null) throw Revert("PolicyChangeActive")
                v.policyNonce += BigInteger.ONE
                v.policy = PolicyView(KIND_RECOVERY, from, approved = false, eta = now() + maxOf(v.delay, DAY), newAddress = addrArg(0))
                v.activity += event(ActivityView.Kind.SecurityChange)
            }
            sel(SafeChainCalls.approvePolicyChange(BigInteger.ZERO)) -> {
                val p = v.policy ?: throw Revert("NoPolicyChange")
                if (!isPrimary && !isRecovery) throw Revert("NotSigner")
                if (from.equals(p.proposer, ignoreCase = true)) throw Revert("SelfApproval")
                v.policy = p.copy(approved = true)
                v.activity += event(ActivityView.Kind.SecurityChange)
            }
            sel(SafeChainCalls.executePolicyChange(BigInteger.ZERO)) -> {
                val p = v.policy ?: throw Revert("NoPolicyChange")
                if (!p.approved) throw Revert("NotApproved")
                if (now() < p.eta) throw Revert("PolicyDelayNotReached")
                v.recovery = p.newAddress
                v.policy = null
                v.activity += event(ActivityView.Kind.RecoveryReplaced, address = p.newAddress)
            }
            sel(SafeChainCalls.cancelPolicyChange()) -> {
                if (!isPrimary && !isRecovery) throw Revert("NotSigner")
                v.policy ?: throw Revert("NoPolicyChange")
                v.policy = null
                v.activity += event(ActivityView.Kind.SecurityChange)
            }
            else -> throw Revert("not available in the demo")
        }
    }

    /** ProtectedTransferEscrow: create (needs the card's approval), cancel before the deadline, settle after. */
    private fun escrow(from: String, selector: String, uintArg: (Int) -> BigInteger, addrArg: (Int) -> String) {
        when (selector) {
            sel(SafeChainCalls.createPayment(ZERO_ADDRESS, ZERO_ADDRESS, BigInteger.ZERO, 0)) -> {
                val recipient = addrArg(1)
                val amount = uintArg(2)
                val protection = uintArg(3).toLong()
                if (recipient.equals(from, ignoreCase = true) || recipient == ZERO_ADDRESS) throw Revert("InvalidRecipient")
                if (protection < HOUR || protection > 30 * DAY) throw Revert("InvalidProtection")
                if (amount > escrowAllowance) throw Revert("ERC20: insufficient allowance")
                if (amount > cardTokens) throw Revert("ERC20: transfer amount exceeds balance")
                cardTokens -= amount
                val id = BigInteger.valueOf(payments.size + 1L)
                payments += P(id, from, recipient, amount, now(), now() + protection)
                checking += event(ActivityView.Kind.PaymentSent, id = id, amount = amount, address = recipient, by = from)
            }
            sel(SafeChainCalls.cancelPayment(BigInteger.ZERO)) -> {
                val p = payments.firstOrNull { it.id == uintArg(0) } ?: throw Revert("NotPending")
                if (p.status != PENDING) throw Revert("NotPending")
                if (!from.equals(p.sender, ignoreCase = true) && !from.equals(p.recipient, ignoreCase = true)) throw Revert("NotAuthorized")
                if (now() >= p.deadline) throw Revert("DeadlinePassed")
                p.status = CANCELLED
                if (p.sender.equals(CARD, ignoreCase = true)) cardTokens += p.amount
                checking += event(ActivityView.Kind.PaymentCancelled, id = p.id, by = from)
            }
            sel(SafeChainCalls.settlePayment(BigInteger.ZERO)) -> {
                val p = payments.firstOrNull { it.id == uintArg(0) } ?: throw Revert("NotPending")
                if (p.status != PENDING) throw Revert("NotPending")
                if (now() < p.deadline) throw Revert("DeadlineNotReached")
                p.status = EXECUTED
                if (p.recipient.equals(CARD, ignoreCase = true)) cardTokens += p.amount
                checking += event(ActivityView.Kind.PaymentSettled, id = p.id, by = from)
            }
            else -> throw Revert("not available in the demo")
        }
    }

    private fun vault(address: String) = vaults.firstOrNull { it.address.equals(address, ignoreCase = true) }

    private fun withdrawal(v: V, id: BigInteger) = v.withdrawals.firstOrNull { it.id == id } ?: throw Revert("NotPending")

    private fun isVoid(v: V, w: W) = w.epoch < v.epoch && w.deadline > (v.lockedAt[w.epoch] ?: Long.MAX_VALUE)

    private fun reserved(v: V) = v.withdrawals.filter { it.status == PENDING }.fold(BigInteger.ZERO) { a, w -> a + w.amount }

    private fun available(v: V) = (v.balance - reserved(v)).max(BigInteger.ZERO)

    private fun view(v: V) = VaultView(
        address = v.address,
        primary = v.primary,
        recovery = v.recovery,
        haven = ZERO_ADDRESS,
        delaySeconds = v.delay,
        locked = v.locked,
        balance = v.balance,
        available = available(v),
        pendingPrimary = ZERO_ADDRESS,
        previousPrimary = ZERO_ADDRESS,
        vetoUntil = 0,
        withdrawals = v.withdrawals.sortedByDescending { it.id }.map { w ->
            val void = w.status == PENDING && isVoid(v, w)
            val state = when {
                w.status == EXECUTED -> 3
                w.status == CANCELLED || void -> 4
                now() < w.deadline -> 1
                else -> 2
            }
            WithdrawalView(w.id, SepoliaConfig.TOKEN, w.to, w.amount, w.requestedAt, w.deadline, w.initiator, toHaven = false, state = state, voided = void)
        },
        policyNonce = v.policyNonce,
        policy = v.policy,
        activity = v.activity.sortedByDescending { it.block },
    )

    private fun event(
        kind: ActivityView.Kind,
        id: BigInteger? = null,
        amount: BigInteger? = null,
        address: String? = null,
        by: String? = null,
        ago: Long = 0,
    ): ActivityView {
        // Blocks are ~12 s apart; past events get an older block so "x h ago" reads right.
        val at = block - BigInteger.valueOf(ago / SECONDS_PER_BLOCK)
        return ActivityView(kind, at, "0x" + "de".repeat(31) + "%02x".format(at.toInt() and 0xff), at.toLong(), id, amount, address, by)
    }

    companion object {
        const val PENDING = 0
        const val EXECUTED = 1
        const val CANCELLED = 2
        private const val SELECTOR = 4
        private const val KIND_RECOVERY = 1
        private const val HOUR = 3_600L
        private const val DAY = 86_400L
        private const val SECONDS_PER_BLOCK = 12L
        private const val START_BLOCK = 12_000_000L

        // Made-up addresses: they belong to nobody and are never sent to the network.
        val CARD = checksumAddress("0xde00000000000000000000000000000000c0de01")
        val PHONE = checksumAddress("0xde000000000000000000000000000000000b0b02")
        val OTHER_WALLET = checksumAddress("0xde00000000000000000000000000000000071203")
        val OLD_VAULT = checksumAddress("0x5afe000000000000000000000000000000000001")
        val VAULT = checksumAddress("0x5afe000000000000000000000000000000000002")
        val RANDOM_ADDRESS = checksumAddress("0x0deb000000000000000000000000000000571b00")
        val FRIEND = checksumAddress("0x7a11000000000000000000000000000000f00d04")

        private fun units(n: Long) = BigInteger.valueOf(n).multiply(BigInteger.TEN.pow(SepoliaConfig.TOKEN_DECIMALS))
        private fun sel(call: ByteArray) = Hex.encode(call.copyOf(SELECTOR))
    }
}
