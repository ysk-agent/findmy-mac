package com.prelina.findmynet

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * SessionManager - session persistence with Keystore-backed encryption.
 *
 * Batch 3 renovation (see BATCH3.md):
 * - The full session-token set (sessionToken, sessionId, scnt, trustToken,
 *   accountCountry, clientId), the account info (dsid, findme_url,
 *   accountName, accountLocked), the webservices JSON, the username and the
 *   last-auth timestamp are serialised as one JSON object, encrypted with
 *   AES-256/GCM under a key held in the Android Keystore, and only the
 *   Base64 ciphertext + IV are stored (in the [PREF_NAME] preferences file).
 *   No plaintext session value is ever written to disk.
 * - The former plaintext "icloud_session" preferences file is deleted on
 *   first construction (delete-and-relogin migration; this is a pre-release
 *   app with no installed users, so no values are migrated).
 * - The password is never stored in any form. There is no password hash /
 *   fingerprint any more: session validity is username match + a present
 *   session token + the 60-day expiry based on last_auth_time.
 * - No user-authentication (biometric / device-credential) gate is placed on
 *   the Keystore key in this batch; that is a separate UX decision.
 */
class SessionManager(context: Context) {

    companion object {
        private const val TAG = "SessionManager"

        // Ciphertext-only store (values are Base64 IV + Base64 AES-GCM ciphertext).
        private const val PREF_NAME = "icloud_session_enc"

        // Legacy plaintext store from before batch 3. Deleted, never read.
        private const val LEGACY_PREF_NAME = "icloud_session"

        private const val KEY_BLOB_IV = "session_blob_iv"
        private const val KEY_BLOB_DATA = "session_blob_data"

        private const val KEYSTORE_ALIAS = "findmynet_session_key"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        // Session Token Keys (names inside the encrypted JSON blob)
        private const val KEY_SESSION_TOKEN = "session_token"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_SCNT = "scnt"
        private const val KEY_TRUST_TOKEN = "trust_token"
        private const val KEY_ACCOUNT_COUNTRY = "account_country"
        private const val KEY_CLIENT_ID = "client_id"

        // Account Info Keys
        private const val KEY_DSID = "dsid"
        private const val KEY_FINDME_URL = "findme_url"
        private const val KEY_ACCOUNT_NAME = "account_name"
        private const val KEY_ACCOUNT_LOCKED = "account_locked"

        // Webservices
        private const val KEY_WEBSERVICES = "webservices"

        // Username (the Apple ID email; stored inside the encrypted blob)
        private const val KEY_USERNAME = "username"

        // Timestamp
        private const val KEY_LAST_AUTH_TIME = "last_auth_time"
        private const val SESSION_EXPIRY_DAYS = 60 // session expires after 60 days
    }

    private val appContext: Context = context.applicationContext
    private val prefs: SharedPreferences =
        appContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)

    init {
        deleteLegacyPlaintextPrefs()
    }

    /**
     * Delete-and-relogin migration: remove the pre-batch-3 plaintext
     * preferences file ("icloud_session") without reading or migrating its
     * values. After an upgrade the user signs in once more and the new
     * session is written only to the encrypted store. Best effort: a failure
     * here must never crash startup.
     */
    private fun deleteLegacyPlaintextPrefs() {
        try {
            val legacy = appContext.getSharedPreferences(LEGACY_PREF_NAME, Context.MODE_PRIVATE)
            if (legacy.all.isNotEmpty()) {
                legacy.edit().clear().apply()
            }
            appContext.deleteSharedPreferences(LEGACY_PREF_NAME)
        } catch (e: Exception) {
            // Best effort only; the legacy values are never read by this class.
        }
    }

    // ------------------------------------------------------------------
    // Keystore / AES-GCM helpers
    // ------------------------------------------------------------------

    /** Return the existing Keystore key, or null if none was created yet. */
    private fun existingKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER)
            keyStore.load(null)
            (keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
        } catch (e: Exception) {
            null
        }
    }

    /** Return the Keystore key, generating an AES-256 GCM key on first use. */
    @Synchronized
    private fun getOrCreateKey(): SecretKey {
        existingKey()?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return keyGenerator.generateKey()
    }

    /** Encrypt [json] and store Base64 IV + ciphertext in [prefs]. */
    private fun writeSessionJson(json: JsonObject) {
        val plain = json.toString().toByteArray(StandardCharsets.UTF_8)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val ciphertext = cipher.doFinal(plain)
        prefs.edit()
            .putString(KEY_BLOB_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .putString(KEY_BLOB_DATA, Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .apply()
    }

    /**
     * Decrypt and parse the stored session blob, or return null when there
     * is no blob, no key, or decryption/parsing fails (for example after a
     * Keystore reset). A null result is treated by callers as "no session",
     * which forces a fresh sign-in rather than a crash.
     */
    private fun readSessionJson(): JsonObject? {
        val ivBase64 = prefs.getString(KEY_BLOB_IV, null) ?: return null
        val dataBase64 = prefs.getString(KEY_BLOB_DATA, null) ?: return null
        val key = existingKey() ?: return null
        return try {
            val iv = Base64.decode(ivBase64, Base64.NO_WRAP)
            val ciphertext = Base64.decode(dataBase64, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            val plain = cipher.doFinal(ciphertext)
            JsonParser.parseString(String(plain, StandardCharsets.UTF_8)).asJsonObject
        } catch (e: Exception) {
            null
        }
    }

    private fun jsonString(json: JsonObject, key: String): String? {
        val element = json.get(key) ?: return null
        if (element.isJsonNull) return null
        return try {
            element.asString
        } catch (e: Exception) {
            null
        }
    }

    private fun jsonBoolean(json: JsonObject, key: String): Boolean {
        val element = json.get(key) ?: return false
        if (element.isJsonNull) return false
        return try {
            element.asBoolean
        } catch (e: Exception) {
            false
        }
    }

    private fun jsonLong(json: JsonObject, key: String): Long {
        val element = json.get(key) ?: return 0L
        if (element.isJsonNull) return 0L
        return try {
            element.asLong
        } catch (e: Exception) {
            0L
        }
    }

    // ------------------------------------------------------------------
    // Public API (signatures kept from the plaintext version, except that
    // no method takes a password any more)
    // ------------------------------------------------------------------

    /**
     * Save the complete session data (encrypted at rest).
     * The password is deliberately not a parameter: it is never persisted.
     */
    fun saveSession(
        username: String,
        sessionToken: String?,
        sessionId: String?,
        scnt: String?,
        trustToken: String?,
        accountCountry: String?,
        clientId: String,
        dsid: String?,
        findmeUrl: String?,
        accountName: String?,
        accountLocked: Boolean = false,
        webservices: String? = null
    ) {
        try {
            val json = JsonObject()

            // Session Tokens
            sessionToken?.let { json.addProperty(KEY_SESSION_TOKEN, it) }
            sessionId?.let { json.addProperty(KEY_SESSION_ID, it) }
            scnt?.let { json.addProperty(KEY_SCNT, it) }
            trustToken?.let { json.addProperty(KEY_TRUST_TOKEN, it) }
            accountCountry?.let { json.addProperty(KEY_ACCOUNT_COUNTRY, it) }
            json.addProperty(KEY_CLIENT_ID, clientId)

            // Account Info
            dsid?.let { json.addProperty(KEY_DSID, it) }
            findmeUrl?.let { json.addProperty(KEY_FINDME_URL, it) }
            accountName?.let { json.addProperty(KEY_ACCOUNT_NAME, it) }
            json.addProperty(KEY_ACCOUNT_LOCKED, accountLocked)

            // Webservices
            webservices?.let { json.addProperty(KEY_WEBSERVICES, it) }

            // Username
            json.addProperty(KEY_USERNAME, username)

            // Timestamp
            json.addProperty(KEY_LAST_AUTH_TIME, System.currentTimeMillis())

            writeSessionJson(json)
        } catch (e: Exception) {
            // A Keystore/encryption failure leaves no (partial) session
            // behind: the previous blob, if any, is untouched and the next
            // save or sign-in retries from a clean state.
        }
    }

    /**
     * Check whether the saved session is still valid: the username matches,
     * a session token is present, and the session is within its 60-day
     * expiry (based on last_auth_time). Validity does not depend on the
     * password in any form.
     */
    fun isSessionValid(username: String): Boolean {
        val json = readSessionJson() ?: return false

        // Username must match
        if (jsonString(json, KEY_USERNAME) != username) {
            return false
        }

        // A session token must be present
        if (jsonString(json, KEY_SESSION_TOKEN).isNullOrEmpty()) {
            return false
        }

        // Session must not be expired (60 days)
        val lastAuthTime = jsonLong(json, KEY_LAST_AUTH_TIME)
        val daysSinceAuth = (System.currentTimeMillis() - lastAuthTime) / (1000 * 60 * 60 * 24)
        if (daysSinceAuth > SESSION_EXPIRY_DAYS) {
            clearSession()
            return false
        }

        return true
    }

    /**
     * Restore the saved session into ICloudService.
     */
    fun restoreSession(iCloudService: ICloudService): Boolean {
        try {
            val json = readSessionJson() ?: return false
            val sessionToken = jsonString(json, KEY_SESSION_TOKEN)
            if (sessionToken.isNullOrEmpty()) {
                return false
            }

            // Restore session tokens
            iCloudService.sessionToken = sessionToken
            iCloudService.sessionId = jsonString(json, KEY_SESSION_ID)
            iCloudService.scnt = jsonString(json, KEY_SCNT)
            iCloudService.trustToken = jsonString(json, KEY_TRUST_TOKEN)
            iCloudService.accountCountry = jsonString(json, KEY_ACCOUNT_COUNTRY)

            // Restore account info (via reflection into private fields)
            val dsid = jsonString(json, KEY_DSID)
            val findmeUrl = jsonString(json, KEY_FINDME_URL)
            val accountName = jsonString(json, KEY_ACCOUNT_NAME)

            try {
                val dsidField = ICloudService::class.java.getDeclaredField("dsid")
                dsidField.isAccessible = true
                dsidField.set(iCloudService, dsid)

                val findmeUrlField = ICloudService::class.java.getDeclaredField("findme_url")
                findmeUrlField.isAccessible = true
                findmeUrlField.set(iCloudService, findmeUrl)

                val accountNameField = ICloudService::class.java.getDeclaredField("accountName")
                accountNameField.isAccessible = true
                accountNameField.set(iCloudService, accountName)
            } catch (e: Exception) {
            }

            return true
        } catch (e: Exception) {
            return false
        }
    }

    /**
     * Clear the saved session (the ciphertext blob). The Keystore key is
     * kept so the next save does not need to regenerate it; a leftover key
     * with no ciphertext grants access to nothing.
     */
    fun clearSession() {
        prefs.edit().clear().apply()
    }

    /**
     * Get the saved username.
     */
    fun getSavedUsername(): String? {
        val json = readSessionJson() ?: return null
        return jsonString(json, KEY_USERNAME)
    }

    /**
     * Get the FindMe URL.
     */
    fun getFindmeUrl(): String? {
        val json = readSessionJson() ?: return null
        return jsonString(json, KEY_FINDME_URL)
    }
}
