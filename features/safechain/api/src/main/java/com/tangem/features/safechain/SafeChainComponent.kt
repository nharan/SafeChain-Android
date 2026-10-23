package com.tangem.features.safechain

import com.tangem.core.decompose.factory.ComponentFactory
import com.tangem.core.ui.decompose.ComposableContentComponent
import com.tangem.domain.models.wallet.UserWalletId

/**
 * SafeChain Protected Savings on Sepolia: a vault whose withdrawals wait out a protection period and can be
 * cancelled, with the Tangem card as the spending key. See github.com/nharan/SafeChain.
 */
interface SafeChainComponent : ComposableContentComponent {

    class Params(val userWalletId: UserWalletId)

    interface Factory : ComponentFactory<Params, SafeChainComponent>
}
