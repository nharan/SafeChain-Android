package com.tangem.sdk.api

import androidx.activity.ComponentActivity
import com.tangem.TangemSdk
import com.tangem.common.card.Card
import com.tangem.common.core.CardSessionRunnable
import com.tangem.common.json.MoshiJsonConverter
import com.tangem.common.services.secure.SecureStorage
import com.tangem.operations.backup.BackupRepo
import com.tangem.operations.backup.BackupService
import com.tangem.operations.backup.PrimaryCard
import com.tangem.sdk.AndroidStringLocator
import com.tangem.sdk.storage.create
import java.lang.ref.WeakReference
import java.util.concurrent.ConcurrentHashMap

class BackupServiceHolder {

    lateinit var backupService: WeakReference<BackupService>
        private set

    /**
     * Strictly RAM-only storage for BackupRepo so that EncryptedBackupData and AccessCode
     * are NEVER written to flash storage / EncryptedSharedPreferences on disk.
     */
    private val inMemoryBackupStorage = object : SecureStorage {
        private val memory = ConcurrentHashMap<String, ByteArray>()

        override fun get(account: String): ByteArray? = memory[account]

        override fun getAsString(account: String): String? = memory[account]?.decodeToString()

        override fun store(data: String, account: String) {
            memory[account] = data.encodeToByteArray()
        }

        override fun store(data: ByteArray, account: String) {
            memory[account] = data.copyOf()
        }

        override fun delete(account: String) {
            memory[account]?.fill(0)
            memory.remove(account)
        }

        override fun storeKey(key: ByteArray, account: String) {
            memory[account] = key.copyOf()
        }
    }

    fun createAndSetService(
        tangemSdk: TangemSdk,
        activity: ComponentActivity,
        primaryCardRunnableDecorator: (PrimaryCard, CardSessionRunnable<Card>) -> CardSessionRunnable<Card> =
            { _, runnable -> runnable },
    ) {
        // Purge any legacy disk-persisted BackupData from EncryptedSharedPreferences
        runCatching {
            SecureStorage.create(activity).delete("BackupData")
        }

        val repo = BackupRepo(
            storage = inMemoryBackupStorage,
            jsonConverter = MoshiJsonConverter.INSTANCE,
        )
        val service = BackupService(
            sdk = tangemSdk,
            repo = repo,
            stringsLocator = AndroidStringLocator(activity),
            primaryCardRunnableDecorator = primaryCardRunnableDecorator,
        )
        backupService = WeakReference(service)
    }
}