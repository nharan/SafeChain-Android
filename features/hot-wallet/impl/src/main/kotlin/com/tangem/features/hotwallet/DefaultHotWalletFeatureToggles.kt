package com.tangem.features.hotwallet

/**
 * Hardened build: the mnemonic must never leave the device. Google Drive backup uploads it (password-encrypted)
 * to the same Google account whose password manager would also hold that password, so both stay off.
 */
internal class DefaultHotWalletFeatureToggles : HotWalletFeatureToggles {

    override val isGoogleDriveBackupEnabled: Boolean
        get() = false

    override val isPasswordManagerEnabled: Boolean
        get() = false
}
