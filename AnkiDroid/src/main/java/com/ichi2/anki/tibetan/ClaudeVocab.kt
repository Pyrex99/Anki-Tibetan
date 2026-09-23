/*
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * Gets Tibetan vocabulary (Tibetan word + English meaning) from Claude via the
 * Anthropic Messages API with structured JSON output. Two entry points:
 *  - [extractFromPhoto]: read the words off a photo of a textbook page
 *  - [suggest]: "give me ten new verbs to learn" style requests
 *
 * Verbs are always returned with their present / future / past stems on the
 * Tibetan side, so irregular verbs are learned in full.
 */

package com.ichi2.anki.tibetan

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArrayBuilder
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

/** A single vocabulary entry. */
@Serializable
data class VocabPair(
    val tibetan: String,
    val english: String,
)

/** Outcome of a Claude request. */
sealed interface ExtractionResult {
    data class Success(
        val pairs: List<VocabPair>,
    ) : ExtractionResult

    /** A user-presentable error message (network, auth, parse, empty, …). */
    data class Failure(
        val message: String,
    ) : ExtractionResult
}

object ClaudeVocab {
    private const val ENDPOINT = "https://api.anthropic.com/v1/messages"
    private const val MODEL = "claude-opus-5"
    private const val ANTHROPIC_VERSION = "2023-06-01"

    /** Retries a safety-classifier refusal on Anthropic's recommended fallback model. */
    private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
    private const val MAX_TOKENS = 16000

    /** How many existing words to send so Claude can avoid suggesting duplicates. */
    private const val MAX_KNOWN_WORDS = 3000

    private val client: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true }

    private const val SYSTEM_PROMPT =
        "You help an English speaker build flashcards for learning Tibetan. " +
            "Each card has a Tibetan side (Tibetan Unicode script) and an English side.\n\n" +
            "Verbs: the Tibetan side must list the verb's present, future and past stems, in that order, " +
            "separated by \" / \" (e.g. \"བྱེད་ / བྱ་ / བྱས་\"). If two stems are identical, repeat the form " +
            "anyway so there are always three. Use the standard dictionary forms; if you are unsure of a stem, " +
            "give your best attested form rather than inventing one. The English side for a verb starts with " +
            "\"to\" (e.g. \"to do, to make\").\n\n" +
            "Other words: the Tibetan side is just the word. If an English meaning has alternatives " +
            "(e.g. \"girl / daughter\"), keep them together on the English side."

    private const val PHOTO_PROMPT =
        "This is a photo of a page from a Tibetan language textbook listing vocabulary. " +
            "Extract every vocabulary entry. For each entry, return the Tibetan word exactly as " +
            "written, and its English meaning. If the entry is a verb, give all three stems " +
            "(present / future / past) even if the page only shows one. " +
            "Ignore item numbers, page numbers, section headings, and any explanatory notes — " +
            "only the word/meaning pairs. Preserve the order they appear on the page."

    /**
     * Reads vocabulary from a photo.
     * @param imageBytes raw bytes of the captured/selected image
     * @param mediaType e.g. "image/jpeg" or "image/png"
     */
    suspend fun extractFromPhoto(
        imageBytes: ByteArray,
        mediaType: String,
        apiKey: String,
    ): ExtractionResult {
        val base64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
        return send(apiKey) {
            addJsonObject {
                put("type", "image")
                putJsonObject("source") {
                    put("type", "base64")
                    put("media_type", mediaType)
                    put("data", base64)
                }
            }
            addJsonObject {
                put("type", "text")
                put("text", PHOTO_PROMPT)
            }
        }
    }

    /**
     * Suggests new vocabulary for a free-form request such as "give me ten new verbs to learn".
     * @param knownWords Tibetan sides of cards already in the collection, to avoid duplicates
     */
    suspend fun suggest(
        request: String,
        knownWords: List<String>,
        apiKey: String,
    ): ExtractionResult {
        val prompt =
            buildString {
                append("Suggest Tibetan vocabulary flashcards for this request:\n\n")
                append(request.trim())
                append("\n\nPrefer common, useful words for a learner. If the request doesn't say how many, suggest 10.")
                if (knownWords.isNotEmpty()) {
                    append("\n\nI already have cards for these words, so don't suggest them again:\n")
                    knownWords.take(MAX_KNOWN_WORDS).joinTo(this, separator = "\n")
                }
            }
        return send(apiKey) {
            addJsonObject {
                put("type", "text")
                put("text", prompt)
            }
        }
    }

    /**
     * Completes cards where the user typed only one side (e.g. English words they want to learn).
     * Returns the same number of cards, in the same order.
     */
    suspend fun fill(
        rows: List<VocabPair>,
        apiKey: String,
    ): ExtractionResult {
        val prompt =
            buildString {
                append("Complete these flashcards. Each line is \"Tibetan | English\"; one side may be empty. ")
                append("Fill in the empty side. Keep the side that was given as written, except that a verb's ")
                append("Tibetan side must have its three stems. If the English is ambiguous, pick the most common meaning ")
                append("and make it clear on the English side (e.g. \"to see\" not \"see\"). ")
                append("Return exactly ${rows.size} cards in the same order.\n\n")
                rows.forEach { append("${it.tibetan} | ${it.english}\n") }
            }
        val result =
            send(apiKey) {
                addJsonObject {
                    put("type", "text")
                    put("text", prompt)
                }
            }
        if (result is ExtractionResult.Success && result.pairs.size != rows.size) {
            return ExtractionResult.Failure("Claude returned a different number of words. Try again.")
        }
        return result
    }

    private suspend fun send(
        apiKey: String,
        content: JsonArrayBuilder.() -> Unit,
    ): ExtractionResult =
        withContext(Dispatchers.IO) {
            if (apiKey.isBlank()) {
                return@withContext ExtractionResult.Failure(
                    "No Anthropic API key set. Add one in Settings → General.",
                )
            }

            val request =
                Request
                    .Builder()
                    .url(ENDPOINT)
                    .header("x-api-key", apiKey)
                    .header("anthropic-version", ANTHROPIC_VERSION)
                    .header("anthropic-beta", FALLBACK_BETA)
                    .header("content-type", "application/json")
                    .post(buildRequestBody(content).toRequestBody("application/json".toMediaType()))
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
                Timber.w(e, "Claude vocab request failed")
                ExtractionResult.Failure("Network error: ${e.localizedMessage}")
            }
        }

    /** Builds the Messages API request with a forced `{cards:[{tibetan,english}]}` output schema. */
    private fun buildRequestBody(content: JsonArrayBuilder.() -> Unit): String {
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
                put("fallbacks", "default")
                put("system", SYSTEM_PROMPT)
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
                        putJsonArray("content", content)
                    }
                }
            }
        return root.toString()
    }

    @Serializable
    private data class MessagesResponse(
        val content: List<ContentBlock> = emptyList(),
        @SerialName("stop_reason")
        val stopReason: String? = null,
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
        when (parsed.stopReason) {
            "refusal" -> return ExtractionResult.Failure("Claude declined this request. Try rephrasing it.")
            "max_tokens" -> return ExtractionResult.Failure("Too many words at once. Try asking for fewer.")
        }
        val text = parsed.content.firstOrNull { it.type == "text" }?.text
        if (text.isNullOrBlank()) {
            return ExtractionResult.Failure("The API returned no text.")
        }
        val wrapper =
            try {
                json.decodeFromString<CardsWrapper>(text)
            } catch (e: Exception) {
                Timber.w(e, "Could not parse cards JSON: %s", text)
                return ExtractionResult.Failure("Could not read the words from Claude's reply.")
            }
        val pairs =
            wrapper.cards
                .map { VocabPair(it.tibetan.trim(), it.english.trim()) }
                .filter { it.tibetan.isNotEmpty() && it.english.isNotEmpty() }
        return if (pairs.isEmpty()) {
            ExtractionResult.Failure("No vocabulary found.")
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
            400 -> "The request could not be processed (bad request)."
            413 -> "The image is too large. Try a smaller photo."
            429 -> "Rate limited by the API. Wait a moment and try again."
            in 500..599 -> "The API is temporarily unavailable. Try again shortly."
            else -> "API error $code."
        }.also { Timber.d("Anthropic error body: %s", body) }
}
