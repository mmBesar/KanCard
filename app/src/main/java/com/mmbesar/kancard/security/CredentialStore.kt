package com.mmbesar.kancard.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Which way the app is being used. */
enum class Mode {
    /** First start: nothing chosen yet. */
    UNSET,

    /** Connected to a Kanboard server. */
    KANBOARD,

    /** Everything stays on this phone. */
    LOCAL,
}

/** Everything needed to talk to one Kanboard server. */
data class Connection(
    /** Full API address, e.g. https://kanboard.lan/jsonrpc.php (http:// is allowed). */
    val endpoint: String,
    val username: String,
    val token: String,
    /** SHA-256 of the self-signed certificate the user approved, or null for normal certificates. */
    val pinnedSha256: String?,
) {
    val isCleartext: Boolean get() = endpoint.startsWith("http://", ignoreCase = true)

    /** Never print the token (it could end up in a crash report or a log by accident). */
    override fun toString(): String =
        "Connection(endpoint=$endpoint, username=$username, token=***, pinnedSha256=$pinnedSha256)"
}

/**
 * Remembers the chosen mode, the server login and the selected project.
 *
 * The server address, username and token are encrypted with a key that lives
 * inside the Android Keystore (it never leaves the phone and is destroyed on
 * uninstall). The pinned fingerprint is public information, so it is stored as-is.
 */
class CredentialStore(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences("kancard_settings", Context.MODE_PRIVATE)

    var mode: Mode
        get() = runCatching { Mode.valueOf(prefs.getString(KEY_MODE, null) ?: "") }
            .getOrDefault(Mode.UNSET)
        set(value) {
            prefs.edit().putString(KEY_MODE, value.name).apply()
        }

    /** The project shown on the board (0 = none chosen yet). */
    var selectedProjectId: Long
        get() = prefs.getLong(KEY_PROJECT, 0L)
        set(value) {
            prefs.edit().putLong(KEY_PROJECT, value).apply()
        }

    // Decrypting needs the Android Keystore, so the result is kept in memory
    // (the screens ask for the connection often).
    @Volatile private var cached: Connection? = null
    @Volatile private var cacheLoaded = false

    fun saveConnection(connection: Connection) {
        cached = connection
        cacheLoaded = true
        prefs.edit()
            .putString(KEY_ENDPOINT, encrypt(connection.endpoint))
            .putString(KEY_USER, encrypt(connection.username))
            .putString(KEY_TOKEN, encrypt(connection.token))
            .putString(KEY_PIN, connection.pinnedSha256)
            .apply()
    }

    /** Returns null if nothing is saved or it cannot be decrypted any more. */
    fun loadConnection(): Connection? {
        if (cacheLoaded) return cached
        val loaded = readConnection()
        cached = loaded
        cacheLoaded = true
        return loaded
    }

    private fun readConnection(): Connection? {
        val endpoint = decrypt(prefs.getString(KEY_ENDPOINT, null)) ?: return null
        val user = decrypt(prefs.getString(KEY_USER, null)) ?: return null
        val token = decrypt(prefs.getString(KEY_TOKEN, null)) ?: return null
        return Connection(endpoint, user, token, prefs.getString(KEY_PIN, null))
    }

    fun clearConnection() {
        cached = null
        cacheLoaded = true
        prefs.edit()
            .remove(KEY_ENDPOINT).remove(KEY_USER).remove(KEY_TOKEN).remove(KEY_PIN)
            .apply()
    }

    // ---- Encryption (AES-GCM with a Keystore key) ----------------------------

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    /** Output is Base64 of: 12-byte random IV followed by the encrypted bytes. */
    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv + encrypted, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String?): String? {
        if (stored == null) return null
        return try {
            val bytes = Base64.decode(stored, Base64.NO_WRAP)
            val iv = bytes.copyOfRange(0, IV_SIZE)
            val encrypted = bytes.copyOfRange(IV_SIZE, bytes.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "kancard_credentials_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_SIZE = 12
        const val TAG_BITS = 128

        const val KEY_MODE = "mode"
        const val KEY_PROJECT = "selected_project"
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_USER = "username"
        const val KEY_TOKEN = "token"
        const val KEY_PIN = "pinned_sha256"
    }
}
