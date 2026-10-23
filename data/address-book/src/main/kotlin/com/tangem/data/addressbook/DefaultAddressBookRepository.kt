package com.tangem.data.addressbook

import arrow.core.Either
import arrow.core.flatMap
import arrow.core.left
import arrow.core.right
import com.tangem.data.addressbook.store.AddressBookBlobStore
import com.tangem.domain.addressbook.crypto.AddressBookCipher
import com.tangem.domain.addressbook.error.AddressBookSyncError
import com.tangem.domain.addressbook.model.AddressBook
import com.tangem.domain.addressbook.model.AddressBookBlob
import com.tangem.domain.addressbook.model.Contact
import com.tangem.domain.addressbook.model.ContactId
import com.tangem.domain.addressbook.repository.AddressBookRepository
import com.tangem.domain.addressbook.time.IsoTimestampProvider
import com.tangem.domain.common.wallets.UserWalletsListRepository
import com.tangem.domain.models.wallet.UserWallet
import com.tangem.domain.models.wallet.UserWalletId
import com.tangem.utils.coroutines.CoroutineDispatcherProvider
import com.tangem.utils.logging.TangemLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.joda.time.DateTime

internal class DefaultAddressBookRepository(
    private val blobStore: AddressBookBlobStore,
    private val cipher: AddressBookCipher,
    private val userWalletsListRepository: UserWalletsListRepository,
    private val timestampProvider: IsoTimestampProvider,
    private val dispatchers: CoroutineDispatcherProvider,
) : AddressBookRepository {

    private val writeMutex = Mutex()

    private val logger = TangemLogger.withTag(LOG_TAG)

    override fun getContacts(userWalletId: UserWalletId): Flow<List<Contact>> {
        return getContactsForWallet(userWalletId)
            .distinctUntilChanged()
            .flowOn(dispatchers.default)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getAllContacts(): Flow<List<Contact>> {
        return userWalletsListRepository.userWallets
            .filterNotNull()
            .flatMapLatest { wallets ->
                val walletsById = wallets.associateBy { it.walletId.stringValue }
                val ids = wallets.mapTo(mutableSetOf()) { it.walletId }
                blobStore.getBlobs(ids).map { blobs ->
                    blobs.flatMap { blob ->
                        walletsById[blob.walletId]?.let { userWallet ->
                            decryptContacts(blob, userWallet)
                        }.orEmpty()
                    }
                }
            }
            .distinctUntilChanged()
            .flowOn(dispatchers.default)
    }

    private fun getContactsForWallet(userWalletId: UserWalletId): Flow<List<Contact>> {
        return blobStore.getBlob(userWalletId).map { blob ->
            val userWallet = blob?.let { findUserWallet(it.walletId) } ?: return@map emptyList()
            decryptContacts(blob, userWallet)
        }
    }

    override suspend fun getContactsSync(userWalletId: UserWalletId): List<Contact> {
        return withContext(dispatchers.io) {
            val blob = blobStore.getBlobSync(userWalletId) ?: return@withContext emptyList()
            val userWallet = findUserWallet(blob.walletId) ?: return@withContext emptyList()
            decryptContacts(blob, userWallet)
        }
    }

    override suspend fun getContact(userWalletId: UserWalletId, name: String): Contact? =
        withContext(dispatchers.default) {
            val blob = blobStore.getBlobSync(userWalletId) ?: return@withContext null
            val userWallet = findUserWallet(blob.walletId) ?: return@withContext null
            decryptContacts(blob, userWallet).find { it.name.value == name }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun isAddressBookCompatible(userWalletId: UserWalletId?): Flow<Boolean> {
        val source = if (userWalletId != null) {
            blobStore.getBlob(userWalletId).map { blob -> blob?.isVersionCompatible != false }
        } else {
            userWalletsListRepository.userWallets
                .filterNotNull()
                .flatMapLatest { wallets ->
                    val ids = wallets.mapTo(mutableSetOf()) { it.walletId }
                    // getBlobs returns only wallets that already have a stored book; wallets without one are
                    // absent here and count as compatible (a fresh book uses the supported version).
                    blobStore.getBlobs(ids).map { blobs -> blobs.all { it.isVersionCompatible } }
                }
        }
        return source.distinctUntilChanged().flowOn(dispatchers.default)
    }

    override suspend fun saveContact(contact: Contact): Either<AddressBookSyncError, Unit> =
        withContext(dispatchers.default) {
            writeMutex.withLock {
                val userWallet = findUserWallet(contact.walletId.stringValue)
                    ?: return@withLock AddressBookSyncError.Unknown.left()
                currentContacts(contact.walletId, userWallet).flatMap { current ->
                    val merged = current.filterNot { it.id == contact.id } + contact
                    persist(userWallet, AddressBook(contacts = merged))
                }
            }
        }

    override suspend fun deleteContact(id: ContactId): Either<AddressBookSyncError, Unit> =
        withContext(dispatchers.default) {
            writeMutex.withLock {
                userWalletsListRepository.userWalletsSync().forEach { userWallet ->
                    val blob = blobStore.getBlobSync(userWallet.walletId) ?: return@forEach
                    if (!blob.isVersionCompatible) return@forEach
                    val addressBook = cipher.decrypt(blob, userWallet).getOrNull() ?: return@forEach
                    if (addressBook.contacts.none { it.id == id }) return@forEach

                    val remaining = addressBook.contacts.filterNot { it.id == id }
                    return@withLock persist(userWallet, addressBook.copy(contacts = remaining))
                }
                // No wallet held the contact — nothing to push, treat as success.
                Unit.right()
            }
        }

    /** Hardened build: the address book lives only on the device, so there is nothing to sync with the backend */
    override suspend fun syncAddressBooks(): Either<AddressBookSyncError, Unit> = Unit.right()

    private fun decryptContacts(blob: AddressBookBlob, userWallet: UserWallet): List<Contact> {
        if (!blob.isVersionCompatible) {
            logger.e(
                "Skipping address book for wallet ${blob.walletId}: version ${blob.version} is newer than " +
                    "supported ${AddressBookBlob.CURRENT_VERSION}",
            )
            return emptyList()
        }
        return cipher.decrypt(blob, userWallet).fold(
            ifLeft = { error ->
                // The cipher already logged the low-level cause; this ties the failure to the read path so QA
                // can see that a stored/synced book (e.g. one created on iOS) could not be shown to the user.
                logger.e("Skipping address book for wallet ${blob.walletId}: decrypt failed with $error")
                emptyList()
            },
            ifRight = { it.contacts },
        )
    }

    /**
     * The contacts currently stored for [userWalletId], as the base a write is merged onto.
     *
     * A missing blob means no book exists yet → an empty base, so the first contact legitimately creates it.
     * But a blob that fails to decrypt must surface as [AddressBookSyncError.DecryptionFailed] rather than an empty
     * list: treating a broken book as empty would let a merged write overwrite the (non-empty) backend copy with a
     * book built from a single new contact, wiping every existing one.
     */
    private suspend fun currentContacts(
        userWalletId: UserWalletId,
        userWallet: UserWallet,
    ): Either<AddressBookSyncError, List<Contact>> {
        val blob = blobStore.getBlobSync(userWalletId) ?: return emptyList<Contact>().right()
        if (!blob.isVersionCompatible) {
            logger.e(
                "Refusing to overwrite address book for wallet $userWalletId: stored version ${blob.version} is " +
                    "newer than supported ${AddressBookBlob.CURRENT_VERSION} — a write would downgrade it",
            )
            return AddressBookSyncError.VersionMismatch.left()
        }
        return cipher.decrypt(blob, userWallet)
            .map { it.contacts }
            .mapLeft { error ->
                logger.e(
                    "Refusing to overwrite address book for wallet $userWalletId: it is stored but decrypt " +
                        "failed with $error",
                )
                AddressBookSyncError.DecryptionFailed
            }
    }

    /**
     * Encrypts [addressBook] and stores it locally. Hardened build: the encrypted book is never pushed to the
     * backend, which would tie the walletId to the user's contacts' activity.
     */
    private suspend fun persist(userWallet: UserWallet, addressBook: AddressBook): Either<AddressBookSyncError, Unit> {
        val updatedAt = DateTime.parse(timestampProvider.now())
        return cipher.encrypt(addressBook, userWallet, updatedAt)
            .mapLeft { error ->
                logger.e("Failed to encrypt address book for wallet ${userWallet.walletId}: $error")
                AddressBookSyncError.Unknown
            }
            .map { blob -> blobStore.storeBlob(blob) }
    }

    private suspend fun findUserWallet(walletId: String): UserWallet? =
        userWalletsListRepository.userWalletsSync().find { it.walletId.stringValue == walletId }

    private companion object {
        const val LOG_TAG = "AddressBook"
    }
}