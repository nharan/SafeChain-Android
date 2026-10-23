package com.tangem.feature.safechain.di

import com.tangem.core.decompose.di.ModelComponent
import com.tangem.core.decompose.model.Model
import com.tangem.feature.safechain.DefaultSafeChainComponent
import com.tangem.feature.safechain.model.SafeChainModel
import com.tangem.features.safechain.SafeChainComponent
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.ClassKey
import dagger.multibindings.IntoMap
import javax.inject.Singleton

@InstallIn(SingletonComponent::class)
@Module
internal interface SafeChainFeatureModule {

    @Binds
    @Singleton
    fun bindFactory(factory: DefaultSafeChainComponent.Factory): SafeChainComponent.Factory
}

@Module
@InstallIn(ModelComponent::class)
internal interface SafeChainModelModule {

    @Binds
    @IntoMap
    @ClassKey(SafeChainModel::class)
    fun bindModel(model: SafeChainModel): Model
}
