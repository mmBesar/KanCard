package com.mmbesar.kancard

import android.app.Application
import com.mmbesar.kancard.data.BoardDatabase
import com.mmbesar.kancard.data.BoardRepository
import com.mmbesar.kancard.security.CredentialStore
import com.mmbesar.kancard.ui.ThemePrefs
import com.mmbesar.kancard.ui.ViewPrefs

/**
 * Creates the long-lived objects once and shares them with the whole app.
 * (A tiny hand-written "dependency container" - no extra framework needed.)
 */
class KanCardApplication : Application() {
    val database: BoardDatabase by lazy { BoardDatabase.create(this) }
    val credentialStore: CredentialStore by lazy { CredentialStore(this) }
    val repository: BoardRepository by lazy { BoardRepository(database.dao(), credentialStore) }
    val themePrefs: ThemePrefs by lazy { ThemePrefs(this) }
    val viewPrefs: ViewPrefs by lazy { ViewPrefs(this) }
}
