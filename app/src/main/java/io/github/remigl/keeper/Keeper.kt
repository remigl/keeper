package io.github.remigl.keeper

import android.content.Context
import io.github.remigl.keeper.log.LogStore
import java.io.File

/** Process-wide single [LogStore], shared by the service, the UI and the provider (one lock, one writer). */
object Keeper {
    /** Inside the app-private internal storage: no storage permission, unreadable by other apps. */
    const val DIRECTORY_NAME = "NotificationLogger"

    @Volatile private var instance: LogStore? = null

    fun store(context: Context): LogStore =
        instance ?: synchronized(this) {
            instance ?: LogStore(File(context.applicationContext.filesDir, DIRECTORY_NAME)).also { instance = it }
        }
}
