/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Reads Tibetan vocabulary (Tibetan word + English meaning) from a photo of a
 * textbook page using the Anthropic Messages API (vision + structured output).
 */

package com.ichi2.anki.tibetan

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import timber.log.Timber
import java.util.concurrent.TimeUnit

/** A single extracted vocabulary entry. */
@Serializable
data class VocabPair(
    val tibetan: String,
    val english: String,
)

/** Outcome of an extraction attempt. */
sealed interface ExtractionResult {
    data class Success(
        val pairs: List<VocabPair>,
    ) : ExtractionResult

    /** A user-presentable error message (network, auth, parse, empty, …). */
    data class Failure(
        val message: String,
    ) : ExtractionResult
}

object PhotoVocabExtractor {
    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val MODEL = "claude-opus-4-8"
    private const val ANTHROPIC_VERSION = "2023-06-01"
    private const val MAX_TOKENS = 8000

    private val client: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }

    private const val PROMPT =
        "This is a photo of a page from a Tibetan language textbook listing vocabulary. " +
            "Extract every vocabulary entry. For each entry, return the Tibetan word exactly as " +
            "written in Tibetan (Unicode) script, and its English meaning. " +
            "Ignore item numbers, page numbers, section headings, and any explanatory notes — " +
            "only the word/meaning pairs. If a meaning has alternatives (e.g. 'girl / daughter'), " +
            "keep them together in the english field. Preserve the order they appear on the page."

    /**
     * @param imageBytes raw bytes of the captured/selected image
     * @param mediaType e.g. "image/jpeg" or "image/png"
     * @param apiKey the user's Anthropic API key
     */
    suspend fun extract(
        imageBytes: ByteArray,
        mediaType: String,
        apiKey: String,
    ): ExtractionResult =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext ExtractionResult.Failure(
                    "No Anthropic API key set. Add one in Settings → General.",
                )
            }

            val base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
            val requestJson = buildRequestBody(base64, mediaType)

            val request =
                Request
                    .Builder()
                    .url(ENDPOINT)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header("content-type", "application/json")
                    .post(requestJson.toRequestBody("application/json".toMediaType()))
                    .build()

            try {
                client.newCall(request).execute().use { response ->
                    val body = response.body.string()
                    if (!response.isSuccessful) {
                        Timber.w("Anthropic API error %d: %s", response.code, body)
                        return@withContext ExtractionResult.Failure(
                            humanReadableError(response.code, body),
                        )
                    }
                    parseResponse(body)
                }
            } catch (e: Exception) {
                Timber.w(e, "Photo vocab extraction failed")
                ExtractionResult.Failure("Network error: ${e.localizedMessage}")
            }
        }

    /** Builds the Messages API request: image + prompt, with a forced JSON-array output schema. */
    private fun buildRequestBody(
        base64Image: String,
        mediaType: String,
    ): String {
        val cardSchema =
            buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("tibetan") { put("type", "string") }
                    putJsonObject("english") { put("type", "string") }
                }
                putJsonArray("required") {
                    add("tibetan")
                    add("english")
                }
                put("additionalProperties", false)
            }

        val root =
            buildJsonObject {
                put("model", MODEL)
                put("max_tokens", MAX_TOKENS)
                putJsonObject("output_config") {
                    putJsonObject("format") {
                        put("type", "json_schema")
                        putJsonObject("schema") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("cards") {
                                    put("type", "array")
                                    put("items", cardSchema)
                                }
                            }
                            putJsonArray("required") { add("cards") }
                            put("additionalProperties", false)
                        }
                    }
                }
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "user")
                        putJsonArray("content") {
                            addJsonObject {
                                put("type", "image")
                                putJsonObject("source") {
                                    put("type", "base64")
                                    put("media_type", mediaType)
                                    put("data", base64Image)
                                }
                            }
                            addJsonObject {
                                put("type", "text")
                                put("text", PROMPT)
                            }
                        }
                    }
                }
            }
        return root.toString()
    }

    @Serializable
    private data class MessagesResponse(
        val content: List<ContentBlock> = emptyList(),
    )

    @Serializable
    private data class ContentBlock(
        val type: String = "",
        val text: String = "",
    )

    @Serializable
    private data class CardsWrapper(
        val cards: List<VocabPair> = emptyList(),
    )

    private fun parseResponse(body: String): ExtractionResult {
        val parsed =
            try {
                json.decodeFromString<MessagesResponse>(body)
            } catch (e: Exception) {
                Timber.w(e, "Could not parse Anthropic response envelope")
                return ExtractionResult.Failure("Unexpected response from the API.")
            }
        val text = parsed.content.firstOrNull { it.type == "text" }?.text
        if (text.isNullOrBlank()) {
            return ExtractionResult.Failure("The API returned no text.")
        }
        val wrapper =
            try {
                json.decodeFromString<CardsWrapper>(text)
            } catch (e: Exception) {
                Timber.w(e, "Could not parse extracted cards JSON: %s", text)
                return ExtractionResult.Failure("Could not read the words from this image.")
            }
        val pairs =
            wrapper.cards
                .map { VocabPair(it.tibetan.trim(), it.english.trim()) }
                .filter { it.tibetan.isNotEmpty() && it.english.isNotEmpty() }
        return if (pairs.isEmpty()) {
            ExtractionResult.Failure("No vocabulary found in this image.")
        } else {
            ExtractionResult.Success(pairs)
        }
    }

    private fun humanReadableError(
        code: Int,
        body: String,
    ): String =
        when (code) {
            401 -> "Invalid API key. Check it in Settings → General."
            400 -> "The image could not be processed (bad request)."
            413 -> "The image is too large. Try a smaller photo."
            429 -> "Rate limited by the API. Wait a moment and try again."
            in 500..599 -> "The API is temporarily unavailable. Try again shortly."
            else -> "API error $code."
        }.also { Timber.d("Anthropic error body: %s", body) }
}
