package com.tangem.feature.safechain.chain

import java.math.BigInteger

/** One thing that happened to a vault, decoded from its event log. */
internal data class ActivityView(
    val kind: Kind,
    val block: BigInteger,
    val txHash: String,
    val logIndex: Long,
    val withdrawalId: BigInteger? = null,
    val amount: BigInteger? = null,
    val address: String? = null,
    val by: String? = null,
    val time: Long? = null,
) {
    enum class Kind {
        Deposited, WithdrawalRequested, HavenWithdrawalRequested, WithdrawalCancelled, WithdrawalExecuted,
        VoidedReleased, Locked, Unlocked, ReplacementScheduled, ReplacementCancelled, PrimaryReplaced,
        RecoveryReplaced, HavenReplaced, DelayChanged, SecurityChange,
    }

    companion object {
        private fun t(sig: String) = VaultReader.eventTopic(sig)

        private val KINDS: Map<String, Kind> = mapOf(
            t("Deposited(address,address,uint256)") to Kind.Deposited,
            t("WithdrawalRequested(uint256,address,address,uint256,address,uint64,uint64)") to Kind.WithdrawalRequested,
            t("HavenWithdrawalRequested(uint256,address,address)") to Kind.HavenWithdrawalRequested,
            t("WithdrawalCancelled(uint256,address)") to Kind.WithdrawalCancelled,
            t("WithdrawalExecuted(uint256,address)") to Kind.WithdrawalExecuted,
            t("VoidedWithdrawalReleased(uint256)") to Kind.VoidedReleased,
            t("Locked(address,uint64)") to Kind.Locked,
            t("Unlocked(address)") to Kind.Unlocked,
            t("PrimaryReplacementScheduled(address,address,uint64)") to Kind.ReplacementScheduled,
            t("PrimaryReplacementCancelled(address)") to Kind.ReplacementCancelled,
            t("PrimaryReplaced(address,address)") to Kind.PrimaryReplaced,
            t("RecoveryReplaced(address,address)") to Kind.RecoveryReplaced,
            t("HavenReplaced(address,address)") to Kind.HavenReplaced,
            t("WithdrawalDelayChanged(uint64,uint64)") to Kind.DelayChanged,
            t("PolicyChangeProposed(uint256,uint8,address,uint64,address,uint64)") to Kind.SecurityChange,
            t("PolicyChangeApproved(uint256,address)") to Kind.SecurityChange,
            t("PolicyChangeCancelled(uint256,address)") to Kind.SecurityChange,
            t("PolicyChangeExecuted(uint256)") to Kind.SecurityChange,
        )

        /** Decodes the events this app shows; others are ignored. */
        fun decode(log: LogEntry): ActivityView? {
            val kind = KINDS[log.topics.firstOrNull()] ?: return null
            val topic = { i: Int -> log.topics.getOrNull(i) }
            val words = Abi.words(Hex.decode(log.data))
            val base = ActivityView(kind, log.blockNumber, log.txHash, log.logIndex)
            fun id() = topic(1)?.let { Hex.toBigInteger(it) }
            fun addr(i: Int) = topic(i)?.let { VaultReader.addressFromTopic(it) }
            return when (kind) {
                Kind.Deposited -> base.copy(by = addr(2), amount = words.firstOrNull()?.let(Abi::uint))
                Kind.WithdrawalRequested -> base.copy(
                    withdrawalId = id(), address = addr(3), amount = words.getOrNull(0)?.let(Abi::uint),
                    by = words.getOrNull(1)?.let(Abi::address),
                )
                Kind.HavenWithdrawalRequested -> base.copy(withdrawalId = id(), address = addr(2), by = addr(3))
                Kind.WithdrawalCancelled, Kind.WithdrawalExecuted -> base.copy(withdrawalId = id(), by = addr(2))
                Kind.VoidedReleased -> base.copy(withdrawalId = id())
                Kind.Locked, Kind.Unlocked -> base.copy(by = addr(1))
                Kind.ReplacementScheduled, Kind.PrimaryReplaced, Kind.RecoveryReplaced, Kind.HavenReplaced ->
                    base.copy(address = addr(2), by = addr(1))
                Kind.ReplacementCancelled -> base.copy(address = addr(1))
                Kind.DelayChanged, Kind.SecurityChange -> base
            }
        }
    }
}
