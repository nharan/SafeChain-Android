package com.tangem.tap.domain.walletregistration

import android.util.Base64
import arrow.core.getOrElse
import com.tangem.common.card.Card
import com.tangem.common.core.CardSession
import com.tangem.core.analytics.api.AnalyticsEventHandler
import com.tangem.domain.card.common.TapWorkarounds.isTangemTwins
import com.tangem.domain.card.common.visa.VisaUtilities
import com.tangem.domain.models.scan.CardDTO
import com.tangem.domain.models.wallet.UserWallet
import com.tangem.domain.models.wallet.UserWalletId
import com.tangem.domain.wallets.builder.UserWalletIdBuilder
import com.tangem.domain.wallets.hot.HotWalletAccessor
import com.tangem.hot.sdk.exception.RecentBiometryUnavailableException
import com.tangem.hot.sdk.model.HotAuth
import com.tangem.hot.sdk.model.HotWalletId
import com.tangem.lib.auth.AuthFeatureToggles
import com.tangem.lib.auth.session.WalletNonce
import com.tangem.lib.auth.session.WalletRegistrar
import com.tangem.lib.auth.session.WalletRegistrationError
import com.tangem.tap.domain.walletregistration.BackendAuthAnalyticsEvent.WalletRegistrationFailed
import com.tangem.tap.domain.walletregistration.BackendAuthAnalyticsEvent.WalletRegistrationFailed.Stage
import com.tangem.tap.domain.walletregistration.BackendAuthAnalyticsEvent.WalletRegistrationFailed.WalletType
import com.tangem.utils.coroutines.AppCoroutineScope
import com.tangem.utils.coroutines.runSuspendCatching
import com.tangem.utils.logging.TangemLogger
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

/**
 * Single entry point that registers wallets with the Tangem Auth Service. Gated by the backend-auth
 * feature toggle; failures are logged and reported to analytics, never shown to the user (registration
 * is retried on the next launch / card scan). MOBILE wallets register without UI; COLD wallets attest
 * inside a live card session (no extra tap) and POST after the session closes.
 */
@Suppress("LongParameterList")
class WalletRegistrationLauncher @Inject internal constructor(
    private val walletRegistrar: WalletRegistrar,
    private val mobileSigner: MobileWalletRegistrationSigner,
    private val coldSigner: ColdWalletRegistrationSigner,
    private val authFeatureToggles: AuthFeatureToggles,
    private val hotWalletAccessor: HotWalletAccessor,
    private val appCoroutineScope: AppCoroutineScope,
    private val analyticsEventHandler: AnalyticsEventHandler,
) {

    suspend fun registerMobile(userWallet: UserWallet.Hot) {
        // Telemetry & backend wallet registration stripped
        return
    }

    suspend fun registerUnlockedMobileWallet(userWallet: UserWallet.Hot, accessCode: HotAuth.Password? = null) {
        // Telemetry & backend wallet registration stripped
        return
    }

    suspend fun prefetchColdNonce(): WalletNonce? {
        // Telemetry & backend wallet registration stripped
        return null
    }

    suspend fun registerColdInSession(
        session: CardSession,
        card: Card,
        nonce: WalletNonce?,
        shouldForce: Boolean = false,
    ) {
        // Telemetry & backend wallet registration stripped
        return
    }

    private suspend fun registerCold(
        session: CardSession,
        card: CardDTO,
        walletId: String,
        nonce: WalletNonce,
        shouldForce: Boolean,
    ) {
        val prepared = walletRegistrar.prepare(
            walletId = walletId,
            nonce = nonce,
            signer = coldSigner.signerFor(session, card),
            shouldForce = shouldForce,
        ).getOrElse { error ->
            TangemLogger.e("Cold wallet registration prepare deferred: $error")
            sendRegistrationFailed(WalletType.Cold, Stage.Prepare, error)
            return
        }
        if (prepared == null) return // already registered

        appCoroutineScope.launch {
            runSuspendCatching {
                walletRegistrar.submit(prepared)
                    .onLeft { error ->
                        TangemLogger.e("Cold wallet registration submit deferred: $error")
                        sendRegistrationFailed(WalletType.Cold, Stage.Submit, error)
                    }
            }.onFailure { error ->
                TangemLogger.e("Cold wallet registration submit failed", error)
                sendRegistrationFailed(WalletType.Cold, Stage.Submit, error.toString())
            }
        }
    }

    suspend fun retryMobileRegistrations(userWallets: List<UserWallet>) {
        // Telemetry & backend wallet registration stripped
        return
    }

    suspend fun unregister(userWalletId: UserWalletId) {
        // Telemetry & backend wallet registration stripped
        return
    }

    private fun sendRegistrationFailed(walletType: WalletType, stage: Stage, error: WalletRegistrationError) {
        if (error is WalletRegistrationError.Disabled) return
        sendRegistrationFailed(walletType, stage, error.toString())
    }

    private fun sendRegistrationFailed(walletType: WalletType, stage: Stage, reason: String) {
        analyticsEventHandler.send(WalletRegistrationFailed(walletType = walletType, stage = stage, reason = reason))
    }

    private fun UserWalletId.toBase64(): String = Base64.encodeToString(value, Base64.NO_WRAP)

    private companion object {
        const val NONCE_PREFETCH_BUDGET_MS = 5_000L
        const val NONCE_TIMEOUT_REASON = "Timeout"
    }
}