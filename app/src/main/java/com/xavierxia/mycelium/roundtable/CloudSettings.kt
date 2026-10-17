package com.xavierxia.mycelium.roundtable

import android.content.Context
import com.xavierxia.mycelium.roundtable.ModelPrices
import com.xavierxia.mycelium.roundtable.ModelSettings
import java.security.SecureRandom

/**
 * Defaults checked against DeepSeek's Models & Pricing page on 2026-10-17.
 * Prices are the off-peak cache-miss rates for deepseek-flash, in US dollars per million tokens.
 */
object DeepSeekDefaults {
    const val BASE_URL = "https://api.deepseek.com"
    const val MODEL = "deepseek-flash"
    const val INPUT_PER_MILLION = 0.15
    const val OUTPUT_PER_MILLION = 0.6
    const val MONTHLY_LIMIT_USD = 5.0
    const val TRAINING_POLICY = "未找到明文条款"
    const val PRIVACY_POLICY_URL = "https://cdn.deepseek.com/policies/zh-CN/deepseek-privacy-policy.html"
}

class CloudSettingsStore(context: Context, private val secrets: SecretStore) {
    private val preferences = context.getSharedPreferences("cloud-model", Context.MODE_PRIVATE)

    fun read(): ModelSettings = ModelSettings(
        baseUrl = preferences.getString("baseUrl", DeepSeekDefaults.BASE_URL)!!,
        model = preferences.getString("model", DeepSeekDefaults.MODEL)!!,
        prices = ModelPrices(
            preferences.getString("input", null)?.toDoubleOrNull() ?: DeepSeekDefaults.INPUT_PER_MILLION,
            preferences.getString("output", null)?.toDoubleOrNull() ?: DeepSeekDefaults.OUTPUT_PER_MILLION,
        ),
        monthlyLimitUsd = preferences.getString("limit", null)?.toDoubleOrNull() ?: DeepSeekDefaults.MONTHLY_LIMIT_USD,
        apiKey = preferences.getString("key", null)?.let { String(secrets.unwrap(it), Charsets.UTF_8) },
    )

    fun keyHint(): String? {
        val key = read().apiKey ?: return null
        return if (key.length <= 4) "已保存" else "····${key.takeLast(4)}"
    }

    fun save(baseUrl: String, model: String, input: Double, output: Double, limit: Double, apiKey: String?) {
        preferences.edit()
            .putString("baseUrl", baseUrl.trim())
            .putString("model", model.trim())
            .putString("input", input.toString())
            .putString("output", output.toString())
            .putString("limit", limit.toString())
            .apply()
        if (!apiKey.isNullOrBlank()) {
            preferences.edit().putString("key", secrets.wrap(apiKey.toByteArray(Charsets.UTF_8))).apply()
        }
    }

    /** A random SQLCipher passphrase, wrapped by the keystore. Created once. */
    fun databasePassphrase(): String {
        val stored = preferences.getString("database", null)
        if (stored != null) return String(secrets.unwrap(stored), Charsets.UTF_8)
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val passphrase = bytes.joinToString("") { "%02x".format(it) }
        preferences.edit().putString("database", secrets.wrap(passphrase.toByteArray(Charsets.UTF_8))).apply()
        return passphrase
    }
}
