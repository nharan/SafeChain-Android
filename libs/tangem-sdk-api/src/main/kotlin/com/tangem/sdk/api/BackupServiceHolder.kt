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

    /**
     * Strong reference retained for the duration of an active backup onboarding session so the JVM garbage collector
     * cannot reclaim [BackupService] between steps while [inMemoryBackupStorage] is in use.
     * Explicitly cleared and zeroized via [clearActiveSession] when onboarding completes or resets.
     */
    @Volatile
    private var activeBackupService: BackupService? = null

    lateinit var backupService: WeakReference<BackupService>
        private set

    private val memory = ConcurrentHashMap<String, ByteArray>()

    /**
     * Strictly RAM-only storage for BackupRepo so that EncryptedBackupData and AccessCode
     * are NEVER written to flash storage / EncryptedSharedPreferences on disk.
     * Returns defensive copies on read and zeroizes buffers before overwrite or deletion.
     */
    private val inMemoryBackupStorage = object : SecureStorage {

        override fun get(account: String): ByteArray? = memory[account]?.copyOf()

        override fun getAsString(account: String): String? = memory[account]?.decodeToString()

        override fun store(data: String, account: String) {
            val encoded = data.encodeToByteArray()
            memory.put(account, encoded)?.fill(0)
        }

        override fun store(data: ByteArray, account: String) {
            memory.put(account, data.copyOf())?.fill(0)
        }

        override fun delete(account: String) {
            memory.remove(account)?.fill(0)
        }

        override fun storeKey(key: ByteArray, account: String) {
            memory.put(account, key.copyOf())?.fill(0)
        }
    }

    fun clearActiveSession() {
        activeBackupService?.discardSavedBackup()
        val keys = memory.keys().toList()
        for (key in keys) {
            memory.remove(key)?.fill(0)
        }
        activeBackupService = null
    }

    fun createAndSetService(
        tangemSdk: TangemSdk,
        activity: ComponentActivity,
        primaryCardRunnableDecorator: (PrimaryCard, CardSessionRunnable<Card>) -> CardSessionRunnable<Card> =
            { _, runnable -> runnable },
    ) {
        clearActiveSession()

        // Purge any legacy disk-persisted BackupData from EncryptedSharedPreferences
        runCatching {
            SecureStorage.create(activity).delete("BackupData")
        }.onFailure { e ->
            android.util.Log.w("BackupServiceHolder", "Failed to purge legacy BackupData key from disk storage", e)
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
        activeBackupService = service
        backupService = WeakReference(service)
    }
}