package com.xavierxia.mycelium.roundtable

import android.content.Context

/**
 * Defaults checked against DeepSeek's documentation on 2026-10-17.
 * The base URL is the OpenAI-compatible endpoint root; models come from `GET /models`.
 */
object DeepSeekDefaults {
    const val BASE_URL = "https://api.deepseek.com"
    const val TRAINING_POLICY = "未找到明文条款"
    const val PRIVACY_POLICY_URL = "https://cdn.deepseek.com/policies/zh-CN/deepseek-privacy-policy.html"
}

/** The keystore-backed [SecretBox] the app uses. */
class KeystoreSecretBox(private val alias: String) : SecretBox {
    private val store = SecretStore(alias)
    override fun wrap(plain: ByteArray): String = store.wrap(plain)
    override fun unwrap(stored: String): ByteArray = store.unwrap(stored)
}

/**
 * Cloud services and their settings, persisted as one JSON blob. Keys inside the blob are wrapped
 * by the keystore; the database passphrase lives in the same file.
 */
class CloudSettingsStore(context: Context, secrets: SecretBox) {
    private val preferences = context.getSharedPreferences("cloud-model", Context.MODE_PRIVATE)
    private val store = CloudStore(
        storage = { preferences.getString("services", null) },
        persist = { blob -> preferences.edit().putString("services", blob).apply() },
        secrets = secrets,
    )
    private val keystore = secrets

    fun services(): List<CloudService> = store.services()

    fun addService(id: String?, name: String, baseUrl: String, apiKey: String?): String =
        store.addService(id, name, baseUrl, apiKey)

    fun removeService(serviceId: String) = store.removeService(serviceId)

    fun apiKey(serviceId: String): String? = store.apiKey(serviceId)

    fun keyHint(serviceId: String): String? = store.keyHint(serviceId)

    fun models(serviceId: String): List<String> = store.models(serviceId)

    fun saveModels(serviceId: String, models: List<String>) = store.saveModels(serviceId, models)

    fun allBaseUrls(): List<String> = store.allBaseUrls()

    fun selection(): CloudSelection? = store.selection()

    fun rememberSelection(serviceId: String, model: String) = store.rememberSelection(serviceId, model)

    /** A random SQLCipher passphrase, wrapped by the keystore. Created once. */
    fun databasePassphrase(): String {
        val stored = preferences.getString("database", null)
        if (stored != null) return String(keystore.unwrap(stored), Charsets.UTF_8)
        val bytes = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val passphrase = bytes.joinToString("") { "%02x".format(it) }
        preferences.edit().putString("database", keystore.wrap(passphrase.toByteArray(Charsets.UTF_8))).apply()
        return passphrase
    }
}
