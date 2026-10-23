package com.tangem.tap.domain.tasks.product

import com.tangem.common.CompletionResult
import com.tangem.common.core.CardSession
import com.tangem.common.core.CardSessionRunnable
import com.tangem.common.core.TangemError
import com.tangem.common.core.TangemSdkError
import com.tangem.common.services.secure.SecureStorage
import com.tangem.operations.attestation.Attestation
import com.tangem.operations.attestation.AttestationTask
import com.tangem.tap.domain.TapSdkError

/**
 * Hardened build: the rule for accepting a card. The SDK lets the user "continue" past a skipped (`VerifiedOffline`)
 * or failed online verification, so a card is only accepted once its certificate has been verified against the
 * manufacturer key — online, or from the signed response cached on an earlier verified scan, both of which report
 * [Attestation.Status.Verified].
 */
internal object ManufacturerAttestationGate {

    fun verificationError(status: Attestation.Status): TangemError? = when (status) {
        Attestation.Status.Verified -> null
        Attestation.Status.VerifiedOffline,
        Attestation.Status.Skipped,
        -> TapSdkError.CardNotVerifiedByManufacturer(isVerificationUnavailable = true)
        Attestation.Status.Failed,
        Attestation.Status.Warning,
        -> TapSdkError.CardNotVerifiedByManufacturer(isVerificationUnavailable = false)
    }

    /**
     * A card session is pinned to the scanned card only by its cardId, which a counterfeit device can simply repeat.
     * The card public key is what the attestation proved, so the card in the session must present that same key.
     */
    fun cardBindingError(expectedCardPublicKey: ByteArray, actualCardPublicKey: ByteArray): TangemError? =
        if (expectedCardPublicKey.contentEquals(actualCardPublicKey)) null else TapSdkError.CardChangedSinceScan()
}

/**
 * Re-attests the card in the current NFC session before a key-material operation: it must be manufacturer-verified
 * and be the very card (same card public key) that was scanned and accepted earlier.
 */
internal class VerifyScannedCardTask(
    private val expectedCardPublicKey: ByteArray,
    private val createAttestation: (SecureStorage) -> CardSessionRunnable<Attestation> = { secureStorage ->
        AttestationTask(AttestationTask.Mode.Normal, secureStorage)
    },
) : CardSessionRunnable<Unit> {

    override fun run(session: CardSession, callback: (result: CompletionResult<Unit>) -> Unit) {
        createAttestation(session.environment.secureStorage).run(session) { result ->
            when (result) {
                is CompletionResult.Success -> {
                    val card = session.environment.card
                    val error = when {
                        card == null -> TangemSdkError.MissingPreflightRead()
                        else -> ManufacturerAttestationGate.verificationError(result.data.status)
                            ?: ManufacturerAttestationGate.cardBindingError(expectedCardPublicKey, card.cardPublicKey)
                    }
                    callback(if (error == null) CompletionResult.Success(Unit) else CompletionResult.Failure(error))
                }
                is CompletionResult.Failure -> callback(CompletionResult.Failure(result.error))
            }
        }
    }
}
