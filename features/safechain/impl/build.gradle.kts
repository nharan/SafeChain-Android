plugins {
    alias(deps.plugins.android.library)
    alias(deps.plugins.kotlin.android)
    alias(deps.plugins.kotlin.kapt)
    alias(deps.plugins.kotlin.serialization)
    alias(deps.plugins.hilt.android)
    id("configuration")
}

android {
    namespace = "com.tangem.feature.safechain.impl"
}

dependencies {
    api(projects.features.safechain.api)

    // region Core
    api(projects.core.decompose)
    api(projects.core.ui)
    api(projects.core.utils)
    // endregion

    // region Domain — wallet lookup, key derivation, card signer
    implementation(projects.domain.models)
    implementation(projects.domain.common)
    implementation(projects.domain.wallets)
    implementation(projects.domain.card)
    implementation(projects.domain.qrScanning)
    implementation(projects.domain.qrScanning.models)
    implementation(projects.common.routing)
    // endregion

    // region Tangem SDK — Keccak, UnmarshalHelper (recovery id), card signing types
    implementation(tangemDeps.blockchain)
    implementation(tangemDeps.card.core)
    // endregion

    // region Network — plain JSON-RPC to public Sepolia endpoints (no Tangem hosts)
    implementation(deps.okHttp)
    implementation(deps.kotlin.serialization)
    // endregion

    // region UI
    api(deps.compose.foundation)
    implementation(deps.compose.material3)
    implementation(deps.compose.ui)
    implementation(deps.compose.ui.tooling)
    implementation(deps.androidx.appCompat)
    implementation(deps.decompose)
    implementation(deps.kotlin.coroutines)
    implementation(deps.lifecycle.compose)
    implementation(deps.lifecycle.runtime.ktx)
    implementation(deps.kotlin.immutable.collections)
    // endregion

    // region DI
    implementation(deps.hilt.android)
    implementation(deps.arrow.core)
    kapt(deps.hilt.kapt)
    // endregion

    testImplementation(deps.test.junit5)
    testImplementation(deps.test.mockk)
    testImplementation(deps.test.truth)
    testImplementation(deps.test.coroutine)
}
