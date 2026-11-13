package com.lcs.codexquota.net

import com.lcs.codexquota.data.QuotaConfig
import com.lcs.codexquota.data.Tokens
import com.lcs.codexquota.data.UsageParser
import com.lcs.codexquota.data.UsageSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

class Unauthorized : IOException("401 from Codex backend")

class HttpFailure(val code: Int, val body: String) : IOException("HTTP $code: $body")

data class PrimeOutcome(
    val ok: Boolean,
    val resetAt: Instant?,
    val status: Int,
    val detail: String?,
)

object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(120, TimeUnit.SECONDS)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}

/**
 * Thin client for the undocumented ChatGPT Codex backend.
 *
 * Endpoints verified against a live Plus account:
 *  - GET  /backend-api/wham/usage   -> quota + reset timestamps
 *  - POST /backend-api/codex/responses (SSE) -> starts a fresh 5h window and
 *    returns the new reset instant in `x-codex-primary-reset-at`.
 */
class CodexClient(private val tokens: Tokens) {

    private fun headers(sse: Boolean = false): Headers {
        val b = Headers.Builder()
            .add("Authorization", "Bearer ${tokens.accessToken}")
            .add("originator", ORIGINATOR)
            .add("User-Agent", USER_AGENT)
            .add("Accept", if (sse) "text/event-stream" else "application/json")
        tokens.accountId?.let { b.add("chatgpt-account-id", it) }
        return b.build()
    }

    suspend fun usage(): UsageSnapshot = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url(BASE + "/wham/usage")
            .headers(headers())
            .get()
            .build()
        Http.client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            when {
                resp.code == 401 -> throw Unauthorized()
                !resp.isSuccessful -> throw HttpFailure(resp.code, text.take(400))
                else -> UsageParser.parse(text)
            }
        }
    }

    /**
     * Send the smallest possible turn. This is what actually opens a new window:
     * Codex starts the 5h clock on the first request after a reset.
     */
    suspend fun prime(cfg: QuotaConfig): PrimeOutcome = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("model", cfg.model)
            put("instructions", cfg.prompt)
            put("input", org.json.JSONArray().put(JSONObject().apply {
                put("type", "message")
                put("role", "user")
                put("content", org.json.JSONArray().put(JSONObject().apply {
                    put("type", "input_text")
                    put("text", cfg.prompt)
                }))
            }))
            put("stream", true)
            put("store", false)
            put("tools", org.json.JSONArray())
            put("tool_choice", "auto")
            put("parallel_tool_calls", false)
            put("reasoning", JSONObject().apply {
                put("effort", "low")
                put("summary", "auto")
            })
            put("include", org.json.JSONArray())
            put("prompt_cache_key", UUID.randomUUID().toString())
        }.toString()

        val req = Request.Builder()
            .url(BASE + "/codex/responses")
            .headers(
                headers(sse = true).newBuilder()
                    .add("Content-Type", "application/json")
                    .add("OpenAI-Beta", "responses=experimental")
                    .add("session_id", UUID.randomUUID().toString())
                    .build()
            )
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        try {
            Http.client.newCall(req).execute().use { resp ->
                // x-codex-primary-reset-at is epoch SECONDS, unlike the millisecond
                // value stored in our own cache.
                val resetAt = parseResetHeader(resp.header("x-codex-primary-reset-at"))
                if (resp.code == 401) throw Unauthorized()
                if (!resp.isSuccessful) {
                    return@withContext PrimeOutcome(
                        ok = false,
                        resetAt = null,
                        status = resp.code,
                        detail = resp.body?.string()?.take(400),
                    )
                }
                // Hang up as soon as the model starts emitting: the window is
                // already open and the remaining tokens are pure waste.
                resp.body?.source()?.use { src ->
                    var lines = 0
                    while (lines < 5000) {
                        val line = src.readUtf8Line() ?: break
                        lines++
                        if (line.startsWith("event: ")) {
                            val ev = line.substring(7).trim()
                            if (ev in STOP_EVENTS) break
                        }
                    }
                }
                PrimeOutcome(true, resetAt, resp.code, null)
            }
        } catch (e: Unauthorized) {
            throw e
        } catch (e: IOException) {
            PrimeOutcome(false, null, -1, e.message)
        }
    }

    suspend fun refresh(refreshToken: String): Tokens = withContext(Dispatchers.IO) {
        val form = FormBody.Builder()
            .add("grant_type", "refresh_token")
            .add("refresh_token", refreshToken)
            .add("client_id", CLIENT_ID)
            .add("scope", "openid profile email offline_access")
            .build()
        val req = Request.Builder()
            .url(TOKEN_URL)
            .post(form)
            .header("Accept", "application/json")
            .build()
        Http.client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw HttpFailure(resp.code, text.take(400))
            val json = JSONObject(text)
            Tokens(
                accessToken = json.getString("access_token"),
                refreshToken = json.optString("refresh_token").ifEmpty { refreshToken },
                accountId = tokens.accountId,
            )
        }
    }

    companion object {
        const val BASE = "https://chatgpt.com/backend-api"
        const val TOKEN_URL = "https://auth.openai.com/oauth/token"
        const val CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann"
        const val ORIGINATOR = "codex_cli_rs"
        const val USER_AGENT = "codex_cli_rs/0.9.0"

        /** SSE events that prove the turn is running and the window has opened. */
        private val STOP_EVENTS = setOf(
            "response.output_text.delta",
            "response.output_item.done",
            "response.completed",
            "codex.rate_limits",
        )

        /** The reset headers carry epoch seconds; keep the conversion in one place. */
        fun parseResetHeader(value: String?): Instant? =
            value?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let(Instant::ofEpochSecond)
    }
}
