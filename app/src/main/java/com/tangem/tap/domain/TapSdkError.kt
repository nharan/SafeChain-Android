package com.tangem.tap.domain

import com.tangem.common.core.TangemError
import com.tangem.wallet.R

sealed class TapSdkError(override val messageResId: Int?) : TangemError(code = 50100) {
    override var customMessage: String = code.toString()

    class CardForDifferentApp : TapSdkError(R.string.alert_unsupported_card)
    class CardNotSupportedByRelease : TapSdkError(R.string.error_wrong_card_type)

    /**
     * Hardened build: the card's certificate was not verified against the manufacturer key. Raised both when online
     * verification was skipped (no network on a card's first scan) and when it failed; either way the card is not
     * accepted, because a counterfeit card passes every check short of this one.
     */
    class CardNotVerifiedByManufacturer(isVerificationUnavailable: Boolean) : TapSdkError(messageResId = null) {
        override var customMessage: String = if (isVerificationUnavailable) {
            "This card could not be verified as a genuine Tangem card. Connect to the internet and scan it again " +
                "— it only needs to be verified once."
        } else {
            "This card failed verification as a genuine Tangem card and will not be used."
        }
    }
}