package com.tangem.data.cloudbackup.repository

import app.cash.turbine.test
import arrow.core.left
import arrow.core.right
import com.google.common.truth.Truth.assertThat
import com.tangem.data.cloudbackup.CloudBackupJson
import com.tangem.data.cloudbackup.crypto.CloudBackupCipher
import com.tangem.data.cloudbackup.crypto.CloudBackupCryptoError
import com.tangem.data.cloudbackup.crypto.CloudBackupFileData
import com.tangem.data.cloudbackup.store.CloudBackupStore
import com.tangem.domain.cloudbackup.models.CloudBackupError
import com.tangem.domain.cloudbackup.models.CloudBackupSecretData
import com.tangem.domain.cloudbackup.models.RestoredCloudBackup
import com.tangem.google.drive.DriveFile
import com.tangem.google.drive.DriveFileListResponse
import com.tangem.google.drive.GoogleDriveApi
import com.tangem.google.drive.GoogleDriveTokenProvider
import com.tangem.utils.coroutines.TestingCoroutineDispatcherProvider
import io.mockk.clearMocks
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
internal class DefaultCloudBackupRepositoryTest {

    private val api: GoogleDriveApi = mockk()
    private val tokenProvider: GoogleDriveTokenProvider = mockk(relaxUnitFun = true)
    private val store = FakeCloudBackupStore()

    // Encryption itself is covered by CloudBackupCipherTest; here the cipher is stubbed so transport tests
    // don't pay the Argon2 cost and stay focused on the Drive API orchestration + error mapping.
    private val cipher: CloudBackupCipher = mockk()

    private val repository = DefaultCloudBackupRepository(
        api = api,
        tokenProvider = tokenProvider,
        store = store,
        cipher = cipher,
        dispatchers = TestingCoroutineDispatcherProvider(),
    )

    private val secret = CloudBackupSecretData(mnemonic = "m".toCharArray(), isPassphraseRequired = false)

    private val fileData = CloudBackupFileData(
        version = 1,
        id = "id",
        name = "Wallet 1",
        walletId = "wallet-id-1",
        createdAt = "2026-01-09T22:13:20Z",
        crypto = CloudBackupFileData.CryptoData(
            cipher = "aes-256-gcm",
            cipherparams = CloudBackupFileData.CipherParams(nonce = "00"),
            ciphertext = "00",
            tag = "00",
            kdf = "argon2id",
            kdfparams = CloudBackupFileData.KdfParams(
                version = 19,
                memory = 64,
                iterations = 1,
                parallelism = 1,
                dklen = 32,
                salt = "00",
            ),
        ),
    )

    @BeforeEach
    fun setUp() {
        clearMocks(api, tokenProvider, cipher)
        coEvery { tokenProvider.getAccessToken(any()) } returns "token".right()
        every { cipher.encrypt(any(), any(), any(), any()) } returns fileData
    }

    @Test
    fun `GIVEN 401 then 200 WHEN readBackup THEN token invalidated AND request retried once`() = runTest {
        // Arrange
        coEvery { api.downloadFileContent(any(), any(), any()) } returnsMany listOf(
            errorResponse(HTTP_UNAUTHORIZED),
            successResponse(CloudBackupJson.encodeToString(fileData)),
        )
        every { cipher.decrypt(any(), any()) } returns SECRET_PAYLOAD.toByteArray(Charsets.UTF_8).right()

        // Act
        val actual = repository.readBackup(fileId = "file-1", password = "p".toCharArray())

        // Assert
        val expected = RestoredCloudBackup(
            walletName = fileData.name,
            secret = CloudBackupSecretData(mnemonic = "m".toCharArray(), isPassphraseRequired = false),
        )
        assertThat(actual).isEqualTo(expected.right())
        coVerify(exactly = 1) { tokenProvider.invalidate() }
        coVerify(exactly = 2) { api.downloadFileContent(any(), any(), any()) }
    }

    @Test
    fun `GIVEN persistent 401 WHEN readBackup THEN retried once then AuthPermissionsMissing`() = runTest {
        // Arrange
        coEvery { api.downloadFileContent(any(), any(), any()) } returns errorResponse(HTTP_UNAUTHORIZED)

        // Act
        val actual = repository.readBackup(fileId = "file-1", password = "p".toCharArray())

        // Assert
        assertThat(actual).isEqualTo(CloudBackupError.AuthPermissionsMissing.left())
        coVerify(exactly = 2) { api.downloadFileContent(any(), any(), any()) }
    }

    @Test
    fun `GIVEN 401 then 200 WHEN readBackup THEN retry requests token non-interactively`() = runTest {
        // Arrange
        val requestedInteractive = mutableListOf<Boolean>()
        coEvery { tokenProvider.getAccessToken(capture(requestedInteractive)) } returns "token".right()
        coEvery { api.downloadFileContent(any(), any(), any()) } returnsMany listOf(
            errorResponse(HTTP_UNAUTHORIZED),
            successResponse(CloudBackupJson.encodeToString(fileData)),
        )
        every { cipher.decrypt(any(), any()) } returns SECRET_PAYLOAD.toByteArray(Charsets.UTF_8).right()

        // Act
        repository.readBackup(fileId = "file-1", password = "p".toCharArray())

        // Assert
        assertThat(requestedInteractive).containsExactly(true, false).inOrder()
    }

    @Test
    fun `GIVEN long name in the file WHEN readBackup THEN full name is taken from the file content`() = runTest {
        // Arrange
        val longName = "Кошелёк ".repeat(n = 40)
        val content = CloudBackupJson.encodeToString(fileData.copy(name = longName))
        coEvery { api.downloadFileContent(any(), any(), any()) } returns successResponse(content)
        every { cipher.decrypt(any(), any()) } returns SECRET_PAYLOAD.toByteArray(Charsets.UTF_8).right()

        // Act
        val actual = repository.readBackup(fileId = "file-1", password = "p".toCharArray())

        // Assert
        assertThat(actual.getOrNull()?.walletName).isEqualTo(longName)
    }

    @Test
    fun `GIVEN wrong password WHEN readBackup THEN WrongPassword`() = runTest {
        // Arrange
        coEvery { api.downloadFileContent(any(), any(), any()) } returns successResponse(CloudBackupJson.encodeToString(fileData))
        every { cipher.decrypt(any(), any()) } returns CloudBackupCryptoError.WrongPassword.left()

        // Act
        val actual = repository.readBackup(fileId = "file-1", password = "p".toCharArray())

        // Assert
        assertThat(actual).isEqualTo(CloudBackupError.WrongPassword.left())
    }

    @Test
    fun `GIVEN malformed content WHEN readBackup THEN InvalidBackupFile`() = runTest {
        // Arrange
        coEvery { api.downloadFileContent(any(), any(), any()) } returns successResponse("not a backup file")

        // Act
        val actual = repository.readBackup(fileId = "file-1", password = "p".toCharArray())

        // Assert
        assertThat(actual).isEqualTo(CloudBackupError.InvalidBackupFile.left())
    }

    @Test
    fun `GIVEN content without walletId WHEN readBackup THEN InvalidBackupFile AND decrypt is not called`() = runTest {
        // Arrange
        val contentWithoutWalletId = CloudBackupJson.encodeToString(fileData)
            .replace("\"walletId\":\"${fileData.walletId}\",", "")
        coEvery { api.downloadFileContent(any(), any(), any()) } returns successResponse(contentWithoutWalletId)

        // Act
        val actual = repository.readBackup(fileId = "file-1", password = "p".toCharArray())

        // Assert
        assertThat(actual).isEqualTo(CloudBackupError.InvalidBackupFile.left())
        verify(exactly = 0) { cipher.decrypt(any(), any()) }
    }

    @Test
    fun `GIVEN stored flag WHEN isBackedUp THEN returns true`() = runTest {
        // Arrange
        store.setBackedUp(WALLET_ID, backedUp = true)

        // Act & Assert
        assertThat(repository.isBackedUp(WALLET_ID)).isTrue()
        assertThat(repository.isBackedUpFlow(WALLET_ID).first()).isTrue()
    }

    @Test
    fun `GIVEN nothing stored WHEN isBackedUp THEN returns false`() = runTest {
        assertThat(repository.isBackedUp(WALLET_ID)).isFalse()
    }

    @Test
    fun `GIVEN setBackedUp true WHEN isBackedUp THEN returns true`() = runTest {
        // Act
        repository.setBackedUp(WALLET_ID, backedUp = true)

        // Assert
        assertThat(repository.isBackedUp(WALLET_ID)).isTrue()
    }

    @Test
    fun `GIVEN backed up WHEN setBackedUp false THEN isBackedUp returns false`() = runTest {
        // Arrange
        repository.setBackedUp(WALLET_ID, backedUp = true)

        // Act
        repository.setBackedUp(WALLET_ID, backedUp = false)

        // Assert
        assertThat(repository.isBackedUp(WALLET_ID)).isFalse()
    }

    @Test
    fun `GIVEN nothing stored WHEN isBackedUpFlow THEN emits false`() = runTest {
        assertThat(repository.isBackedUpFlow(WALLET_ID).first()).isFalse()
    }

    @Test
    fun `GIVEN setBackedUp true WHEN isBackedUpFlow THEN emits true`() = runTest {
        // Arrange
        repository.setBackedUp(WALLET_ID, backedUp = true)

        // Assert
        assertThat(repository.isBackedUpFlow(WALLET_ID).first()).isTrue()
    }

    @Test
    fun `GIVEN isBackedUpFlow collected WHEN backup state toggles THEN emits each change`() = runTest {
        repository.isBackedUpFlow(WALLET_ID).test {
            // initial state — nothing stored
            assertThat(awaitItem()).isFalse()

            repository.setBackedUp(WALLET_ID, backedUp = true)
            assertThat(awaitItem()).isTrue()

            repository.setBackedUp(WALLET_ID, backedUp = false)
            assertThat(awaitItem()).isFalse()

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `GIVEN any backup WHEN uploadBackup THEN refused AND nothing is encrypted or sent to Drive`() = runTest {
        // Act
        val actual = repository.uploadBackup(
            walletId = WALLET_ID,
            walletName = "Wallet",
            createdAtMillis = 0L,
            secret = secret,
            password = "p".toCharArray(),
        )

        // Assert
        assertThat(actual).isEqualTo(CloudBackupError.CloudUnavailable.left())
        verify(exactly = 0) { cipher.encrypt(any(), any(), any(), any()) }
        coVerify(exactly = 0) { tokenProvider.getAccessToken(any()) }
        coVerify(exactly = 0) { api.createFile(any(), any(), any()) }
        coVerify(exactly = 0) { api.uploadFileContent(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `GIVEN backup without createdAt appProperty WHEN findBackups THEN createdAtMillis from createdTime`() = runTest {
        // Arrange
        val createdTime = "2024-01-15T10:30:00Z"
        coEvery { api.listFiles(any(), any(), any()) } returns Response.success(
            DriveFileListResponse(
                files = listOf(
                    DriveFile(
                        id = "f1",
                        name = "Wallet.backup.json",
                        createdTime = createdTime,
                        appProperties = mapOf("walletId" to "w1"),
                    ),
                ),
            ),
        )

        // Act
        val result = repository.findBackups(interactive = false)

        // Assert
        assertThat(result.getOrNull()?.single()?.createdAtMillis)
            .isEqualTo(Instant.parse(createdTime).toEpochMilliseconds())
    }

    @Test
    fun `GIVEN incremented file name WHEN findBackups THEN backupName drops the extension`() = runTest {
        // Arrange
        coEvery { api.listFiles(any(), any(), any()) } returns Response.success(
            DriveFileListResponse(
                files = listOf(
                    DriveFile(
                        id = "f1",
                        name = "Wallet (1).backup.json",
                        appProperties = mapOf("walletId" to "w1", "walletName" to "Wallet"),
                    ),
                ),
            ),
        )

        // Act
        val result = repository.findBackups(interactive = false)

        // Assert
        assertThat(result.getOrNull()?.single()?.backupName).isEqualTo("Wallet (1)")
    }

    @Test
    fun `GIVEN file with malformed content WHEN findBackups with validation THEN file is filtered out`() = runTest {
        // Arrange
        coEvery { api.listFiles(any(), any(), any()) } returns Response.success(
            DriveFileListResponse(files = listOf(driveFile(id = "f1"))),
        )
        coEvery { api.downloadFileContent(any(), any(), any()) } returns successResponse("{}")

        // Act
        val result = repository.findBackups(interactive = false, validateContent = true)

        // Assert
        assertThat(result.getOrNull()).isEmpty()
    }

    @Test
    fun `GIVEN file with supported content WHEN findBackups with validation THEN file is kept`() = runTest {
        // Arrange
        coEvery { api.listFiles(any(), any(), any()) } returns Response.success(
            DriveFileListResponse(files = listOf(driveFile(id = "f1"))),
        )
        coEvery { api.downloadFileContent(any(), any(), any()) } returns
            successResponse(CloudBackupJson.encodeToString(fileData))
        every { cipher.isSupportedFormat(any()) } returns true

        // Act
        val result = repository.findBackups(interactive = false, validateContent = true)

        // Assert
        assertThat(result.getOrNull()?.single()?.fileId).isEqualTo("f1")
    }

    @Test
    fun `GIVEN file with unsupported format WHEN findBackups with validation THEN file is filtered out`() = runTest {
        // Arrange
        coEvery { api.listFiles(any(), any(), any()) } returns Response.success(
            DriveFileListResponse(files = listOf(driveFile(id = "f1"))),
        )
        coEvery { api.downloadFileContent(any(), any(), any()) } returns
            successResponse(CloudBackupJson.encodeToString(fileData))
        every { cipher.isSupportedFormat(any()) } returns false

        // Act
        val result = repository.findBackups(interactive = false, validateContent = true)

        // Assert
        assertThat(result.getOrNull()).isEmpty()
    }

    @Test
    fun `GIVEN content download fails WHEN findBackups with validation THEN file is kept`() = runTest {
        // Arrange
        coEvery { api.listFiles(any(), any(), any()) } returns Response.success(
            DriveFileListResponse(files = listOf(driveFile(id = "f1"))),
        )
        coEvery { api.downloadFileContent(any(), any(), any()) } returns errorResponse(HTTP_INTERNAL_ERROR)

        // Act
        val result = repository.findBackups(interactive = false, validateContent = true)

        // Assert
        assertThat(result.getOrNull()?.single()?.fileId).isEqualTo("f1")
    }

    @Test
    fun `GIVEN validation disabled WHEN findBackups THEN content is not downloaded`() = runTest {
        // Arrange
        coEvery { api.listFiles(any(), any(), any()) } returns Response.success(
            DriveFileListResponse(files = listOf(driveFile(id = "f1"))),
        )

        // Act
        val result = repository.findBackups(interactive = false)

        // Assert
        assertThat(result.getOrNull()?.single()?.fileId).isEqualTo("f1")
        coVerify(exactly = 0) { api.downloadFileContent(any(), any(), any()) }
    }

    @Test
    fun `GIVEN non-interactive validation WHEN findBackups THEN token never requested interactively`() = runTest {
        // Arrange
        val requestedInteractive = mutableListOf<Boolean>()
        coEvery { tokenProvider.getAccessToken(capture(requestedInteractive)) } returns "token".right()
        coEvery { api.listFiles(any(), any(), any()) } returns Response.success(
            DriveFileListResponse(files = listOf(driveFile(id = "f1"))),
        )
        coEvery { api.downloadFileContent(any(), any(), any()) } returns
            successResponse(CloudBackupJson.encodeToString(fileData))
        every { cipher.isSupportedFormat(any()) } returns true

        // Act
        repository.findBackups(interactive = false, validateContent = true)

        // Assert
        assertThat(requestedInteractive).isNotEmpty()
        assertThat(requestedInteractive).doesNotContain(true)
    }

    @Test
    fun `GIVEN list throws non-IO exception WHEN findBackups THEN ReadError not Unknown`() = runTest {
        // Arrange
        coEvery { api.listFiles(any(), any(), any()) } throws IllegalStateException("conversion error")

        // Act
        val result = repository.findBackups(interactive = false)

        // Assert
        assertThat(result.isLeft()).isTrue()
        result.onLeft { assertThat(it).isInstanceOf(CloudBackupError.ReadError::class.java) }
    }

    @Test
    fun `GIVEN delete throws non-IO exception WHEN deleteBackup THEN WriteError not Unknown`() = runTest {
        // Arrange
        coEvery { api.deleteFile(any(), any()) } throws IllegalStateException("unexpected error")

        // Act
        val result = repository.deleteBackup(fileId = "f1")

        // Assert
        assertThat(result.isLeft()).isTrue()
        result.onLeft { assertThat(it).isInstanceOf(CloudBackupError.WriteError::class.java) }
    }

    private fun driveFile(id: String): DriveFile = DriveFile(
        id = id,
        name = "Wallet.backup.json",
        appProperties = mapOf("walletId" to "w1"),
    )

    private fun errorResponse(code: Int): Response<ResponseBody> = Response.error(code, "".toResponseBody(null))

    private fun successResponse(content: String): Response<ResponseBody> =
        Response.success(content.toResponseBody(JSON_MEDIA_TYPE))

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
        const val HTTP_INTERNAL_ERROR = 500
        const val WALLET_ID = "wallet-1"
        const val SECRET_PAYLOAD = """{"mnemonic":"m","passphraseRequired":0}"""
        val JSON_MEDIA_TYPE = "application/json".toMediaType()
    }
}

private class FakeCloudBackupStore : CloudBackupStore {

    private val walletIds = MutableStateFlow<Set<String>>(emptySet())

    override fun getBackedUpWalletIds(): Flow<Set<String>> = walletIds

    override suspend fun setBackedUp(walletId: String, backedUp: Boolean) {
        walletIds.update { if (backedUp) it + walletId else it - walletId }
    }
}