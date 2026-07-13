package hr.smocnica.core.data.remote

import hr.smocnica.core.data.local.PendingOperationEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import javax.inject.Inject
import javax.inject.Singleton

enum class ApplyStatus { APPLIED, ALREADY_APPLIED, CONFLICT }

data class ApplyResult(val status: ApplyStatus, val revision: Long)

interface OperationGateway {
    suspend fun apply(operation: PendingOperationEntity): ApplyResult
}

@Singleton
class FirebaseOperationGateway @Inject constructor(
    private val client: FirebaseCallableClient,
    private val json: Json,
) : OperationGateway {
    override suspend fun apply(operation: PendingOperationEntity): ApplyResult {
        val payload: JsonObject = json.parseToJsonElement(operation.payloadJson).jsonObject
        val result = client.call(
            "applyOperation",
            mapOf(
                "operationId" to operation.operationId,
                "pantryId" to operation.pantryId,
                "aggregateType" to operation.aggregateType,
                "aggregateId" to operation.aggregateId,
                "baseRevision" to operation.baseRevision,
                "payload" to jsonElementToAny(payload),
                "deviceId" to operation.deviceId,
                "deviceDisplayName" to operation.deviceName,
                "clientCreatedAt" to operation.createdAt,
            ),
        )
        return ApplyResult(
            status = ApplyStatus.valueOf(result["status"]?.toString() ?: "APPLIED"),
            revision = (result["revision"] as? Number)?.toLong() ?: operation.baseRevision + 1,
        )
    }

    private fun jsonElementToAny(element: kotlinx.serialization.json.JsonElement): Any? = when (element) {
        is kotlinx.serialization.json.JsonNull -> null
        is kotlinx.serialization.json.JsonPrimitive -> when {
            element.isString -> element.content
            element.content == "true" || element.content == "false" -> element.content.toBoolean()
            else -> element.content.toLongOrNull() ?: element.content.toDoubleOrNull() ?: element.content
        }
        is kotlinx.serialization.json.JsonArray -> element.map(::jsonElementToAny)
        is kotlinx.serialization.json.JsonObject -> element.mapValues { jsonElementToAny(it.value) }
    }
}

