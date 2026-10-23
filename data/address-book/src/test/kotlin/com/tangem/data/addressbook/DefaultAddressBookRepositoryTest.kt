package com.tangem.data.addressbook

import arrow.core.left
import arrow.core.right
import com.google.common.truth.Truth.assertThat
import com.tangem.data.addressbook.store.AddressBookBlobStore
import com.tangem.domain.addressbook.crypto.AddressBookCipher
import com.tangem.domain.addressbook.error.AddressBookCryptoError
import com.tangem.domain.addressbook.error.AddressBookSyncError
import com.tangem.domain.addressbook.model.*
import com.tangem.domain.addressbook.time.IsoTimestampProvider
import com.tangem.domain.common.wallets.UserWalletsListRepository
import com.tangem.domain.models.wallet.UserWallet
import com.tangem.domain.models.wallet.UserWalletId
import com.tangem.utils.coroutines.TestingCoroutineDispatcherProvider
import io.mockk.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class DefaultAddressBookRepositoryTest {

    private val blobStore: AddressBookBlobStore = mockk()
    private val cipher: AddressBookCipher = mockk()
    private val userWalletsListRepository: UserWalletsListRepository = mockk()
    private val timestampProvider: IsoTimestampProvider = mockk()

    private val userWallet: UserWallet = mockk {
        every { walletId } returns UserWalletId(WALLET_A)
    }

    private val repository = DefaultAddressBookRepository(
        blobStore = blobStore,
        cipher = cipher,
        userWalletsListRepository = userWalletsListRepository,
        timestampProvider = timestampProvider,
        dispatchers = TestingCoroutineDispatcherProvider(),
    )

    @BeforeEach
    fun setup() {
        clearMocks(blobStore, cipher, userWalletsListRepository, timestampProvider)
        every { timestampProvider.now() } returns TIMESTAMP
        coEvery { userWalletsListRepository.userWalletsSync() } returns listOf(userWallet)
    }

    @Test
    fun `GIVEN decryptable blob WHEN getContacts THEN emits decrypted contacts`() = runTest {
        // Arrange
        val contact = createContact(id = "c1", name = "Alice")
        val blob = createBlob()
        every { blobStore.getBlob(UserWalletId(WALLET_A)) } returns flowOf(blob)
        every { cipher.decrypt(blob, userWallet) } returns AddressBook(listOf(contact)).right()

        // Act
        val result = repository.getContacts(UserWalletId(WALLET_A)).first()

        // Assert
        assertThat(result).containsExactly(contact)
    }

    @Test
    fun `GIVEN multiple wallets WHEN getAllContacts THEN emits contacts from all wallets`() = runTest {
        // Arrange
        val contact = createContact(id = "c1", name = "Alice")
        val blob = createBlob()
        every { userWalletsListRepository.userWallets } returns MutableStateFlow(listOf(userWallet))
        every { blobStore.getBlobs(setOf(UserWalletId(WALLET_A))) } returns flowOf(listOf(blob))
        every { cipher.decrypt(blob, userWallet) } returns AddressBook(listOf(contact)).right()

        // Act
        val result = repository.getAllContacts().first()

        // Assert
        assertThat(result).containsExactly(contact)
    }

    @Test
    fun `GIVEN no blob WHEN getContacts THEN emits empty`() = runTest {
        // Arrange
        every { blobStore.getBlob(UserWalletId(WALLET_A)) } returns flowOf(null)

        // Act
        val result = repository.getContacts(UserWalletId(WALLET_A)).first()

        // Assert
        assertThat(result).isEmpty()
    }

    @Test
    fun `GIVEN decryption fails WHEN getContacts THEN emits empty`() = runTest {
        // Arrange
        val blob = createBlob()
        every { blobStore.getBlob(UserWalletId(WALLET_A)) } returns flowOf(blob)
        every { cipher.decrypt(blob, userWallet) } returns AddressBookCryptoError.DecryptionFailed.left()

        // Act
        val result = repository.getContacts(UserWalletId(WALLET_A)).first()

        // Assert
        assertThat(result).isEmpty()
    }

    @Test
    fun `GIVEN stored book WHEN saveContact THEN stores merged book locally`() = runTest {
        // Arrange
        val existing = createContact(id = "c1", name = "Alice")
        val added = createContact(id = "c2", name = "Bob")
        val storedBlob = createBlob()
        coEvery { blobStore.getBlobSync(UserWalletId(WALLET_A)) } returns storedBlob
        every { cipher.decrypt(storedBlob, userWallet) } returns AddressBook(listOf(existing)).right()
        val bookSlot = slot<AddressBook>()
        val newBlob = createBlob(ciphertext = "beefdead")
        every { cipher.encrypt(capture(bookSlot), userWallet, any()) } returns newBlob.right()
        coEvery { blobStore.storeBlob(newBlob) } returns Unit

        // Act
        val result = repository.saveContact(added)

        // Assert
        assertThat(result).isEqualTo(Unit.right())
        assertThat(bookSlot.captured).isEqualTo(AddressBook(listOf(existing, added)))
        coVerify(exactly = 1) { blobStore.storeBlob(newBlob) }
    }

    @Test
    fun `GIVEN no stored book WHEN saveContact THEN stores new book locally`() = runTest {
        // Arrange
        val contact = createContact(id = "c1", name = "Alice")
        coEvery { blobStore.getBlobSync(UserWalletId(WALLET_A)) } returns null
        val bookSlot = slot<AddressBook>()
        val newBlob = createBlob()
        every { cipher.encrypt(capture(bookSlot), userWallet, any()) } returns newBlob.right()
        coEvery { blobStore.storeBlob(newBlob) } returns Unit

        // Act
        val result = repository.saveContact(contact)

        // Assert
        assertThat(result).isEqualTo(Unit.right())
        assertThat(bookSlot.captured).isEqualTo(AddressBook(listOf(contact)))
        coVerify(exactly = 1) { blobStore.storeBlob(newBlob) }
    }

    @Test
    fun `GIVEN stored book that fails to decrypt WHEN saveContact THEN returns DecryptionFailed and stores nothing`() =
        runTest {
            // Arrange
            val storedBlob = createBlob()
            coEvery { blobStore.getBlobSync(UserWalletId(WALLET_A)) } returns storedBlob
            every { cipher.decrypt(storedBlob, userWallet) } returns AddressBookCryptoError.DecryptionFailed.left()

            // Act
            val result = repository.saveContact(createContact(id = "c2", name = "Bob"))

            // Assert
            assertThat(result).isEqualTo(AddressBookSyncError.DecryptionFailed.left())
            verify(exactly = 0) { cipher.encrypt(any(), any(), any()) }
            coVerify(exactly = 0) { blobStore.storeBlob(any()) }
        }

    @Test
    fun `GIVEN existing contact id WHEN saveContact THEN replaces it`() = runTest {
        // Arrange
        val original = createContact(id = "c1", name = "Alice")
        val updated = createContact(id = "c1", name = "Alice Updated")
        val storedBlob = createBlob()
        coEvery { blobStore.getBlobSync(UserWalletId(WALLET_A)) } returns storedBlob
        every { cipher.decrypt(storedBlob, userWallet) } returns AddressBook(listOf(original)).right()
        val bookSlot = slot<AddressBook>()
        val newBlob = createBlob(ciphertext = "beefdead")
        every { cipher.encrypt(capture(bookSlot), userWallet, any()) } returns newBlob.right()
        coEvery { blobStore.storeBlob(newBlob) } returns Unit

        // Act
        val result = repository.saveContact(updated)

        // Assert
        assertThat(result).isEqualTo(Unit.right())
        assertThat(bookSlot.captured).isEqualTo(AddressBook(listOf(updated)))
        coVerify(exactly = 1) { blobStore.storeBlob(newBlob) }
    }

    @Test
    fun `GIVEN contact in wallet WHEN deleteContact THEN stores book without it locally`() = runTest {
        // Arrange
        val kept = createContact(id = "c1", name = "Alice")
        val removed = createContact(id = "c2", name = "Bob")
        val storedBlob = createBlob()
        coEvery { blobStore.getBlobSync(UserWalletId(WALLET_A)) } returns storedBlob
        every { cipher.decrypt(storedBlob, userWallet) } returns AddressBook(listOf(kept, removed)).right()
        val bookSlot = slot<AddressBook>()
        val newBlob = createBlob(ciphertext = "beefdead")
        every { cipher.encrypt(capture(bookSlot), userWallet, any()) } returns newBlob.right()
        coEvery { blobStore.storeBlob(newBlob) } returns Unit

        // Act
        val result = repository.deleteContact(ContactId("c2"))

        // Assert
        assertThat(result).isEqualTo(Unit.right())
        assertThat(bookSlot.captured).isEqualTo(AddressBook(listOf(kept)))
        coVerify(exactly = 1) { blobStore.storeBlob(newBlob) }
    }

    @Test
    fun `GIVEN wallets WHEN syncAddressBooks THEN returns right AND stores nothing`() = runTest {
        // Arrange
        val walletB: UserWallet = mockk { every { walletId } returns UserWalletId(WALLET_B) }
        coEvery { userWalletsListRepository.userWalletsSync() } returns listOf(userWallet, walletB)

        // Act
        val result = repository.syncAddressBooks()

        // Assert
        assertThat(result).isEqualTo(Unit.right())
        coVerify(exactly = 0) { blobStore.storeBlob(any()) }
    }

    @Test
    fun `GIVEN matching name WHEN getContact THEN returns it`() = runTest {
        // Arrange
        val alice = createContact(id = "c1", name = "Alice")
        val bob = createContact(id = "c2", name = "Bob")
        val blob = createBlob()
        coEvery { blobStore.getBlobSync(UserWalletId(WALLET_A)) } returns blob
        every { cipher.decrypt(blob, userWallet) } returns AddressBook(listOf(alice, bob)).right()

        // Act
        val result = repository.getContact(UserWalletId(WALLET_A), name = "Bob")

        // Assert
        assertThat(result).isEqualTo(bob)
    }

    @Test
    fun `GIVEN stored version newer than supported WHEN getContacts THEN emits empty without decrypting`() = runTest {
        // Arrange
        val blob = createBlob(version = "2.0")
        every { blobStore.getBlob(UserWalletId(WALLET_A)) } returns flowOf(blob)

        // Act
        val result = repository.getContacts(UserWalletId(WALLET_A)).first()

        // Assert
        assertThat(result).isEmpty()
        verify(exactly = 0) { cipher.decrypt(any(), any()) }
    }

    @Test
    fun `GIVEN stored version older than supported WHEN getContacts THEN decrypts normally`() = runTest {
        // Arrange
        val contact = createContact(id = "c1", name = "Alice")
        val blob = createBlob(version = "0.9")
        every { blobStore.getBlob(UserWalletId(WALLET_A)) } returns flowOf(blob)
        every { cipher.decrypt(blob, userWallet) } returns AddressBook(listOf(contact)).right()

        // Act
        val result = repository.getContacts(UserWalletId(WALLET_A)).first()

        // Assert
        assertThat(result).containsExactly(contact)
    }

    @Test
    fun `GIVEN stored version newer than supported WHEN saveContact THEN VersionMismatch and stores nothing`() =
        runTest {
            // Arrange
            val storedBlob = createBlob(version = "2.0")
            coEvery { blobStore.getBlobSync(UserWalletId(WALLET_A)) } returns storedBlob

            // Act
            val result = repository.saveContact(createContact(id = "c2", name = "Bob"))

            // Assert
            assertThat(result).isEqualTo(AddressBookSyncError.VersionMismatch.left())
            verify(exactly = 0) { cipher.decrypt(any(), any()) }
            coVerify(exactly = 0) { blobStore.storeBlob(any()) }
        }

    @Test
    fun `GIVEN stored version older than supported WHEN saveContact THEN stores book upgraded to current version`() =
        runTest {
            // Arrange
            val storedBlob = createBlob(version = "0.9")
            coEvery { blobStore.getBlobSync(UserWalletId(WALLET_A)) } returns storedBlob
            every { cipher.decrypt(storedBlob, userWallet) } returns AddressBook(emptyList()).right()
            every { cipher.encrypt(any(), userWallet, any()) } returns createBlob().right()
            val storedSlot = slot<AddressBookBlob>()
            coEvery { blobStore.storeBlob(capture(storedSlot)) } returns Unit

            // Act
            val result = repository.saveContact(createContact(id = "c1", name = "Alice"))

            // Assert
            assertThat(result).isEqualTo(Unit.right())
            assertThat(storedSlot.captured.version).isEqualTo(AddressBookBlob.CURRENT_VERSION)
        }

    @Test
    fun `GIVEN contact wallet version newer than supported WHEN deleteContact THEN book left untouched`() = runTest {
        // Arrange
        val storedBlob = createBlob(version = "2.0")
        coEvery { blobStore.getBlobSync(UserWalletId(WALLET_A)) } returns storedBlob

        // Act
        val result = repository.deleteContact(ContactId("c2"))

        // Assert
        assertThat(result).isEqualTo(Unit.right())
        verify(exactly = 0) { cipher.decrypt(any(), any()) }
        coVerify(exactly = 0) { blobStore.storeBlob(any()) }
    }

    @Test
    fun `GIVEN no book WHEN isAddressBookCompatible for wallet THEN true`() = runTest {
        // Arrange
        every { blobStore.getBlob(UserWalletId(WALLET_A)) } returns flowOf(null)

        // Act
        val result = repository.isAddressBookCompatible(UserWalletId(WALLET_A)).first()

        // Assert
        assertThat(result).isTrue()
    }

    @Test
    fun `GIVEN version at or below supported WHEN isAddressBookCompatible for wallet THEN true`() = runTest {
        // Arrange
        every { blobStore.getBlob(UserWalletId(WALLET_A)) } returns flowOf(createBlob(version = "0.9"))

        // Act
        val result = repository.isAddressBookCompatible(UserWalletId(WALLET_A)).first()

        // Assert
        assertThat(result).isTrue()
    }

    @Test
    fun `GIVEN version newer than supported WHEN isAddressBookCompatible for wallet THEN false`() = runTest {
        // Arrange
        every { blobStore.getBlob(UserWalletId(WALLET_A)) } returns flowOf(createBlob(version = "2.0"))

        // Act
        val result = repository.isAddressBookCompatible(UserWalletId(WALLET_A)).first()

        // Assert
        assertThat(result).isFalse()
    }

    @Test
    fun `GIVEN one wallet book newer WHEN isAddressBookCompatible for all THEN false`() = runTest {
        // Arrange
        val walletB: UserWallet = mockk { every { walletId } returns UserWalletId(WALLET_B) }
        every { userWalletsListRepository.userWallets } returns MutableStateFlow(listOf(userWallet, walletB))
        every { blobStore.getBlobs(setOf(UserWalletId(WALLET_A), UserWalletId(WALLET_B))) } returns
            flowOf(listOf(createBlob(walletId = WALLET_A), createBlob(walletId = WALLET_B, version = "2.0")))

        // Act
        val result = repository.isAddressBookCompatible().first()

        // Assert
        assertThat(result).isFalse()
    }

    @Test
    fun `GIVEN all wallet books supported WHEN isAddressBookCompatible for all THEN true`() = runTest {
        // Arrange
        every { userWalletsListRepository.userWallets } returns MutableStateFlow(listOf(userWallet))
        every { blobStore.getBlobs(setOf(UserWalletId(WALLET_A))) } returns
            flowOf(listOf(createBlob(version = "0.9")))

        // Act
        val result = repository.isAddressBookCompatible().first()

        // Assert
        assertThat(result).isTrue()
    }

    private fun createContact(id: String, name: String, iconColor: String = "KekColor"): Contact = Contact(
        id = ContactId(id),
        walletId = UserWalletId(WALLET_A),
        name = ContactName(name).getOrNull()!!,
        icon = "",
        iconColor = iconColor,
        createdAt = TIMESTAMP,
        updatedAt = TIMESTAMP,
        addresses = emptyList(),
    )

    private fun createBlob(
        walletId: String = WALLET_A,
        version: String = AddressBookBlob.CURRENT_VERSION,
        ciphertext: String = "deadbeef",
    ): AddressBookBlob = AddressBookBlob(
        version = version,
        walletId = walletId,
        updatedAt = TIMESTAMP,
        nonce = "00112233445566778899aabb",
        ciphertext = ciphertext,
        authTag = "cafebabecafebabecafebabecafebabe",
    )

    private companion object {
        const val WALLET_A = "0a0a0a"
        const val WALLET_B = "0b0b0b"
        const val TIMESTAMP = "2026-05-22T09:00:00.000Z"
    }
}
