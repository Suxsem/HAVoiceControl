package com.suxsem.havoicecontrol

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class IntentRequest(
    val text: String,
    val conversation_id: String? = null,
)

@Serializable
data class IntentResponse(
    val continue_conversation: Boolean,
    val conversation_id: String,
    val response: ConversationResponse
)

@Serializable
data class ConversationResponse(
    val speech: SpeechData,
    val language: String,
)

@Serializable
data class SpeechData(
    val plain: SpeechContent? = null,
    val ssml: SpeechContent? = null
) {
    val text: String?
        get() = plain?.speech ?: ssml?.speech

    val isSsml: Boolean
        get() = ssml != null
}

@Serializable
data class SpeechContent(
    val speech: String,
)

class HomeAssistantConversationClient (
    url: String,
    port: Int,
    private val token: String
) {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                coerceInputValues = true
            })
        }
    }

    private val baseUrl = "${url.removeSuffix("/")}:$port/api/conversation/process"

    /**
     * Processa il testo inviandolo a Home Assistant.
     * @param text Il comando vocale o testuale.
     * @param conversationId ID opzionale per mantenere il contesto della conversazione.
     */
    suspend fun process(text: String, conversationId: String? = null): Result<IntentResponse> {
        return try {
            val response: IntentResponse = client.post(baseUrl) {
                header(HttpHeaders.Authorization, "Bearer $token")
                contentType(ContentType.Application.Json)
                setBody(IntentRequest(text = text, conversation_id = conversationId))
            }.body()
            Result.success(response)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun close() {
        client.close()
    }
}