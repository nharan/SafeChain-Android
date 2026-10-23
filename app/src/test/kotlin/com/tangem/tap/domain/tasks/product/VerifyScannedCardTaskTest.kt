package com.tangem.tap.domain.tasks.product

import com.google.common.truth.Truth.assertThat
import com.tangem.common.CompletionResult
import com.tangem.common.card.Card
import com.tangem.common.core.CardSession
import com.tangem.common.core.CardSessionRunnable
import com.tangem.common.core.TangemSdkError
import com.tangem.operations.attestation.Attestation
import com.tangem.tap.domain.TapSdkError
import com.tangem.test.core.ProvideTestModels
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.params.ParameterizedTest

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class VerifyScannedCardTaskTest {

    private val session: CardSession = mockk(relaxed = true)
    private val card: Card = mockk()

    @BeforeEach
    fun setUp() {
        every { session.environment.card } returns card
        every { card.cardPublicKey } returns SCANNED_CARD_KEY
    }

    @ParameterizedTest
    @ProvideTestModels
    fun `GIVEN attestation status WHEN verify scanned card THEN only Verified is accepted`(model: StatusModel) {
        // Act
        val actual = runTask(attestationResult(status = model.status))

        // Assert
        if (model.isAccepted) {
            assertThat(actual).isInstanceOf(CompletionResult.Success::class.java)
        } else {
            val error = (actual as CompletionResult.Failure).error
            assertThat(error).isInstanceOf(TapSdkError.CardNotVerifiedByManufacturer::class.java)
        }
    }

    @Test
    fun `GIVEN a different card tapped after the scan WHEN verify scanned card THEN rejected as changed card`() {
        // Arrange
        every { card.cardPublicKey } returns OTHER_CARD_KEY

        // Act
        val actual = runTask(attestationResult(status = Attestation.Status.Verified))

        // Assert
        val error = (actual as CompletionResult.Failure).error
        assertThat(error).isInstanceOf(TapSdkError.CardChangedSinceScan::class.java)
    }

    @Test
    fun `GIVEN attestation fails to run WHEN verify scanned card THEN its error is returned`() {
        // Arrange
        val sdkError = TangemSdkError.CardVerificationFailed()

        // Act
        val actual = runTask(CompletionResult.Failure(sdkError))

        // Assert
        assertThat((actual as CompletionResult.Failure).error).isSameInstanceAs(sdkError)
    }

    @Test
    fun `GIVEN no card read in the session WHEN verify scanned card THEN rejected`() {
        // Arrange
        every { session.environment.card } returns null

        // Act
        val actual = runTask(attestationResult(status = Attestation.Status.Verified))

        // Assert
        assertThat((actual as CompletionResult.Failure).error)
            .isInstanceOf(TangemSdkError.MissingPreflightRead::class.java)
    }

    private fun attestationResult(status: Attestation.Status): CompletionResult<Attestation> {
        val attestation: Attestation = mockk { every { this@mockk.status } returns status }
        return CompletionResult.Success(attestation)
    }

    private fun runTask(attestationResult: CompletionResult<Attestation>): CompletionResult<Unit> {
        val fakeAttestation = object : CardSessionRunnable<Attestation> {
            override fun run(session: CardSession, callback: (result: CompletionResult<Attestation>) -> Unit) {
                callback(attestationResult)
            }
        }
        var result: CompletionResult<Unit>? = null
        VerifyScannedCardTask(
            expectedCardPublicKey = SCANNED_CARD_KEY,
            createAttestation = { fakeAttestation },
        ).run(session) { result = it }
        return requireNotNull(result)
    }

    data class StatusModel(val status: Attestation.Status, val isAccepted: Boolean)

    private fun provideTestModels() = listOf(
        StatusModel(status = Attestation.Status.Verified, isAccepted = true),
        StatusModel(status = Attestation.Status.VerifiedOffline, isAccepted = false),
        StatusModel(status = Attestation.Status.Skipped, isAccepted = false),
        StatusModel(status = Attestation.Status.Warning, isAccepted = false),
        StatusModel(status = Attestation.Status.Failed, isAccepted = false),
    )

    private companion object {
        val SCANNED_CARD_KEY = byteArrayOf(0x02, 0x11, 0x22, 0x33)
        val OTHER_CARD_KEY = byteArrayOf(0x02, 0x44, 0x55, 0x66)
    }
}
