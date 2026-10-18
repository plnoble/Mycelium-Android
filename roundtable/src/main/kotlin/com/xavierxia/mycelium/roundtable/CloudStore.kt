package com.xavierxia.mycelium.roundtable

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

data class CloudService(val id: String, val name: String, val baseUrl: String, val hasKey: Boolean)

data class SavedModel(val serviceId: String, val model: String)

data class CloudSelection(val serviceId: String, val model: String)

/** Wraps API keys at rest. The app binds this to AndroidKeyStore; tests use a fake. */
interface SecretBox {
    fun wrap(plain: ByteArray): String
    fun unwrap(stored: String): ByteArray
}

/**
 * Services, their keys and the models the user saved. Pure Kotlin so the add/remove/multi-select
 * rules are testable off-device; the caller owns where the blob lives.
 */
class CloudStore(
    private val storage: () -> String?,
    private val persist: (String) -> Unit,
    private val secrets: SecretBox,
) {
    fun services(): List<CloudService> = read().services.map {
        CloudService(it.id, it.name, it.baseUrl, it.storedKey != null)
    }

    /** Adds a service, or updates it when [id] is known. A blank key keeps the stored one. */
    fun addService(id: String?, name: String, baseUrl: String, apiKey: String?): String {
        val data = read()
        val cleanName = name.trim()
        val cleanUrl = baseUrl.trim().trimEnd('/')
        require(cleanName.isNotEmpty()) { "服务名称不能为空" }
        require(cleanUrl.isNotEmpty()) { "接口地址不能为空" }
        val existing = id?.let { serviceId -> data.services.firstOrNull { it.id == serviceId } }
        val key = if (apiKey.isNullOrBlank()) {
            existing?.storedKey
        } else {
            secrets.wrap(apiKey.toByteArray(Charsets.UTF_8))
        }
        val serviceId = existing?.id ?: UUID.randomUUID().toString()
        val replacement = StoredService(serviceId, cleanName, cleanUrl, key)
        // Editing keeps the service where it was in the list; adding appends.
        val updated = if (existing != null) {
            data.services.map { if (it.id == serviceId) replacement else it }
        } else {
            data.services + replacement
        }
        write(data.copy(services = updated))
        return serviceId
    }

    fun removeService(serviceId: String) {
        val data = read()
        write(
            data.copy(
                services = data.services.filterNot { it.id == serviceId },
                models = data.models - serviceId,
                selection = data.selection?.takeIf { it.serviceId != serviceId },
            ),
        )
    }

    fun apiKey(serviceId: String): String? = read().services
        .firstOrNull { it.id == serviceId }
        ?.storedKey
        ?.let { stored -> runCatching { String(secrets.unwrap(stored), Charsets.UTF_8) }.getOrNull() }

    fun keyHint(serviceId: String): String? = apiKey(serviceId)?.let { key ->
        if (key.length <= 4) "已保存" else "····${key.takeLast(4)}"
    }

    fun models(serviceId: String): List<String> = read().models[serviceId].orEmpty()

    /** Replaces the saved set for one service. A selection pointing at a dropped model is cleared. */
    fun saveModels(serviceId: String, models: List<String>) {
        val data = read()
        val clean = models.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val selection = data.selection?.takeIf { it.serviceId != serviceId || it.model in clean }
        write(data.copy(models = data.models + (serviceId to clean), selection = selection))
    }

    fun allBaseUrls(): List<String> = read().services.map { it.baseUrl }.distinct()

    fun selection(): CloudSelection? = read().selection

    fun rememberSelection(serviceId: String, model: String) {
        write(read().copy(selection = CloudSelection(serviceId, model)))
    }

    private fun read(): StoreData {
        val text = storage() ?: return StoreData()
        return try {
            val json = JSONObject(text)
            val services = json.optJSONArray("services")?.let { array ->
                List(array.length()) { index ->
                    val item = array.getJSONObject(index)
                    val key = if (item.isNull("key")) null else item.optString("key").ifEmpty { null }
                    StoredService(item.getString("id"), item.getString("name"), item.getString("baseUrl"), key)
                }
            }.orEmpty()
            val modelsJson = json.optJSONObject("models")
            val models = modelsJson?.let { objectJson ->
                objectJson.keys().asSequence().associateWith { serviceId ->
                    objectJson.optJSONArray(serviceId)?.let { array ->
                        List(array.length()) { index -> array.getString(index) }
                    }.orEmpty()
                }
            }.orEmpty()
            val selection = json.optJSONObject("selection")?.let {
                CloudSelection(it.getString("serviceId"), it.getString("model"))
            }
            StoreData(services, models, selection)
        } catch (_: Exception) {
            StoreData()
        }
    }

    private fun write(data: StoreData) {
        val json = JSONObject()
        val services = JSONArray()
        data.services.forEach { service ->
            services.put(
                JSONObject()
                    .put("id", service.id)
                    .put("name", service.name)
                    .put("baseUrl", service.baseUrl)
                    .put("key", service.storedKey ?: JSONObject.NULL),
            )
        }
        val models = JSONObject()
        data.models.forEach { (serviceId, list) ->
            models.put(serviceId, JSONArray(list))
        }
        json.put("services", services)
        json.put("models", models)
        json.put("selection", data.selection?.let { JSONObject().put("serviceId", it.serviceId).put("model", it.model) } ?: JSONObject.NULL)
        persist(json.toString())
    }

    private data class StoredService(val id: String, val name: String, val baseUrl: String, val storedKey: String?)

    private data class StoreData(
        val services: List<StoredService> = emptyList(),
        val models: Map<String, List<String>> = emptyMap(),
        val selection: CloudSelection? = null,
    )
}
