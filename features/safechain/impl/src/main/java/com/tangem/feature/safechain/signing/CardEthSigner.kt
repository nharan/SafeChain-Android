package com.tangem.feature.safechain.signing

import com.tangem.blockchain.common.Blockchain
import com.tangem.blockchain.common.TransactionSigner
import com.tangem.blockchain.common.Wallet
import com.tangem.common.CompletionResult
import com.tangem.common.card.EllipticCurve
import com.tangem.common.core.TangemError
import com.tangem.common.core.TangemSdkError
import com.tangem.common.extensions.ByteArrayKey
import com.tangem.common.extensions.toDecompressedPublicKey
import com.tangem.domain.card.common.TapWorkarounds.isTangemTwins
import com.tangem.domain.card.models.TwinKey
import com.tangem.domain.card.repository.CardSdkConfigRepository
import com.tangem.domain.common.wallets.UserWalletsListRepository
import com.tangem.domain.common.wallets.getSyncStrict
import com.tangem.domain.models.wallet.UserWallet
import com.tangem.domain.models.wallet.UserWalletId
import com.tangem.domain.wallets.derivations.DerivationsRepository
import com.tangem.domain.wallets.derivations.derivationStyleProvider
import com.tangem.feature.safechain.chain.EvmSignature
import com.tangem.feature.safechain.chain.Rsv
import com.tangem.feature.safechain.chain.addressOfPublicKey
import javax.inject.Inject

internal sealed class SafeChainSignerError(message: String) : Exception(message) {
    class NotACard : SafeChainSignerError("SafeChain needs a Tangem card wallet (the card is the spending key).")
    class NoKey : SafeChainSignerError("This wallet has no secp256k1 key.")
    class Cancelled : SafeChainSignerError("Signing was cancelled.")
    class Card(cause: TangemError) : SafeChainSignerError("Card error: ${cause.customMessage}")
}

/** The card's Ethereum account: the same key and address the Tangem app shows for Ethereum. */
internal data class EthAccount(val address: String, val publicKey: Wallet.PublicKey)

/**
 * Signs SafeChain transactions with the Tangem card, the same way the app signs elsewhere
 * (data/polymarket's DefaultPolymarketTypedDataSigner): the card signs a 32-byte hash it is given and
 * returns r‖s. The hash is built and shown to the user by this app before the tap, because the card has
 * no screen; the protocol's undo window is what makes that acceptable.
 */
internal class CardEthSigner @Inject constructor(
    private val userWalletsListRepository: UserWalletsListRepository,
    private val derivationsRepository: DerivationsRepository,
    private val cardSdkConfigRepository: CardSdkConfigRepository,
) {

    /** May tap the card once, if the Ethereum key was never derived on this phone. */
    suspend fun account(userWalletId: UserWalletId): EthAccount {
        val wallet = userWalletsListRepository.getSyncStrict(userWalletId)
        if (wallet !is UserWallet.Cold) throw SafeChainSignerError.NotACard()
        val seedKey = wallet.scanResponse.card.wallets.firstOrNull { it.curve == EllipticCurve.Secp256k1 }?.publicKey
            ?: throw SafeChainSignerError.NoKey()

        // The app's own Ethereum path for this card (m/44'/60'/0'/0/0 on HD cards); null on non-HD cards,
        // whose seed key is the Ethereum key. Blockchain.Ethereum, not EthereumTestnet: the testnet path can
        // differ (coin type 1), and we want the same address as the app's Ethereum receive screen.
        val path = Blockchain.Ethereum.derivationPath(wallet.derivationStyleProvider.getDerivationStyle())
            ?: return EthAccount(
                address = addressOfPublicKey(seedKey.toDecompressedPublicKey()),
                publicKey = Wallet.PublicKey(seedKey = seedKey, derivationType = null),
            )

        val key = ByteArrayKey(seedKey)
        val xpub = derivationsRepository.getExistingDerivedKeys(userWalletId, key)[path]
            ?: derivationsRepository.derivePublicKeys(userWalletId, mapOf(key to listOf(path))).getValue(key)[path]
            ?: throw SafeChainSignerError.NoKey()

        return EthAccount(
            address = addressOfPublicKey(xpub.publicKey.toDecompressedPublicKey()),
            publicKey = Wallet.PublicKey(
                seedKey = seedKey,
                derivationType = Wallet.PublicKey.DerivationType.Plain(Wallet.HDKey(extendedPublicKey = xpub, path = path)),
            ),
        )
    }

    /** Taps the card to sign [hash]; returns the canonical signature with its recovery id. */
    suspend fun sign(userWalletId: UserWalletId, account: EthAccount, hash: ByteArray): Rsv {
        val wallet = userWalletsListRepository.getSyncStrict(userWalletId)
        if (wallet !is UserWallet.Cold) throw SafeChainSignerError.NotACard()
        return when (val result = signer(wallet).sign(hash, account.publicKey)) {
            is CompletionResult.Success -> EvmSignature.fromCard(result.data, hash, account.publicKey)
            is CompletionResult.Failure -> throw when (val e = result.error) {
                is TangemSdkError.UserCancelled -> SafeChainSignerError.Cancelled()
                else -> SafeChainSignerError.Card(e)
            }
        }
    }

    private fun signer(wallet: UserWallet.Cold): TransactionSigner {
        val card = wallet.scanResponse.card
        val isCardNotBackedUp = card.backupStatus?.isActive != true && !card.isTangemTwins
        return cardSdkConfigRepository.getCommonSigner(
            cardId = card.cardId.takeIf { isCardNotBackedUp },
            twinKey = TwinKey.getOrNull(scanResponse = wallet.scanResponse),
            userWalletId = wallet.walletId,
        )
    }
}
