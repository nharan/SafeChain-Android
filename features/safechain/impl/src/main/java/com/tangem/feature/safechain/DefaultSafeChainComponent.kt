package com.tangem.feature.safechain

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tangem.core.decompose.context.AppComponentContext
import com.tangem.core.decompose.model.getOrCreateModel
import com.tangem.feature.safechain.model.SafeChainModel
import com.tangem.feature.safechain.ui.SafeChainScreen
import com.tangem.features.safechain.SafeChainComponent
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject

internal class DefaultSafeChainComponent @AssistedInject constructor(
    @Assisted appComponentContext: AppComponentContext,
    @Assisted params: SafeChainComponent.Params,
) : SafeChainComponent, AppComponentContext by appComponentContext {

    private val model: SafeChainModel = getOrCreateModel(params)

    @Composable
    override fun Content(modifier: Modifier) {
        val state by model.state.collectAsStateWithLifecycle()
        SafeChainScreen(state = state, actions = model, modifier = modifier)
    }

    @AssistedFactory
    interface Factory : SafeChainComponent.Factory {
        override fun create(context: AppComponentContext, params: SafeChainComponent.Params): DefaultSafeChainComponent
    }
}
