package com.lcs.codexquota.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "quota")

/** A line in the in-app activity log. */
data class Event(
    val at: Instant,
    val kind: Kind,
    val message: String,
) {
    enum class Kind { PRIME, SKIP, FAIL, AUTH, SCHEDULE, INFO }

    fun toJson(): JSONObject = JSONObject().apply {
        put("at", at.toEpochMilli())
        put("kind", kind.name)
        put("msg", message)
    }

    companion object {
        fun fromJson(o: JSONObject): Event = Event(
            at = Instant.ofEpochMilli(o.optLong("at", 0L)),
            kind = runCatching { Kind.valueOf(o.optString("kind", "INFO")) }.getOrDefault(Kind.INFO),
            message = o.optString("msg", ""),
        )
    }
}

/** Persisted runtime state surfaced to the UI. */
data class RuntimeState(
    val nextActivation: Instant? = null,
    val lastPrime: Instant? = null,
    val lastResult: String? = null,
    val tokensPresent: Boolean = false,
    val accountId: String? = null,
    val log: List<Event> = emptyList(),
    val cached: UsageSnapshot? = null,
)

class Prefs private constructor(private val app: Context) {

    private object K {
        val ENABLED = booleanPreferencesKey("enabled")
        val START_H = intPreferencesKey("start_h")
        val START_M = intPreferencesKey("start_m")
        val CUTOFF_H = intPreferencesKey("cutoff_h")
        val CUTOFF_M = intPreferencesKey("cutoff_m")
        val WINDOW_MIN = intPreferencesKey("window_min")
        val GRACE_S = intPreferencesKey("grace_s")
        val MODEL = stringPreferencesKey("model")
        val PROMPT = stringPreferencesKey("prompt")

        val ACCESS = stringPreferencesKey("tok_access")
        val REFRESH = stringPreferencesKey("tok_refresh")
        val ACCOUNT = stringPreferencesKey("tok_account")

        val NEXT = stringPreferencesKey("st_next")
        val LAST_PRIME = stringPreferencesKey("st_last_prime")
        val LAST_RESULT = stringPreferencesKey("st_last_result")
        val LOG = stringPreferencesKey("st_log")
        val CACHED = stringPreferencesKey("st_cached")
    }

    val configFlow: Flow<QuotaConfig> = app.dataStore.data.map { readConfig(it) }
    val stateFlow: Flow<RuntimeState> = app.dataStore.data.map { readState(it) }

    suspend fun config(): QuotaConfig = readConfig(app.dataStore.data.first())

    suspend fun state(): RuntimeState = readState(app.dataStore.data.first())

    suspend fun saveConfig(cfg: QuotaConfig) {
        app.dataStore.edit { p ->
            p[K.ENABLED] = cfg.enabled
            p[K.START_H] = cfg.startHour
            p[K.START_M] = cfg.startMinute
            p[K.CUTOFF_H] = cfg.cutoffHour
            p[K.CUTOFF_M] = cfg.cutoffMinute
            p[K.WINDOW_MIN] = cfg.windowMinutes
            p[K.GRACE_S] = cfg.graceSeconds
            p[K.MODEL] = cfg.model
            p[K.PROMPT] = cfg.prompt
        }
    }

    suspend fun saveTokens(tokens: Tokens) {
        app.dataStore.edit { p ->
            p[K.ACCESS] = tokens.accessToken
            tokens.refreshToken?.let { p[K.REFRESH] = it }
            tokens.accountId?.let { p[K.ACCOUNT] = it }
        }
    }

    suspend fun tokens(): Tokens? {
        val p = app.dataStore.data.first()
        val access = p[K.ACCESS] ?: return null
        return Tokens(access, p[K.REFRESH], p[K.ACCOUNT])
    }

    suspend fun clearTokens() {
        app.dataStore.edit { it.remove(K.ACCESS); it.remove(K.REFRESH) }
    }

    suspend fun setNextActivation(at: Instant?) {
        app.dataStore.edit { p ->
            if (at == null) p.remove(K.NEXT) else p[K.NEXT] = at.toEpochMilli().toString()
        }
    }

    suspend fun setLastPrime(at: Instant, result: String) {
        app.dataStore.edit { p ->
            p[K.LAST_PRIME] = at.toEpochMilli().toString()
            p[K.LAST_RESULT] = result
        }
    }

    suspend fun cacheUsage(snap: UsageSnapshot) {
        app.dataStore.edit { it[K.CACHED] = snap.toJson().toString() }
    }

    suspend fun log(event: Event) {
        app.dataStore.edit { p ->
            val arr = JSONArray(p[K.LOG] ?: "[]")
            arr.put(event.toJson())
            val kept = JSONArray()
            val start = maxOf(0, arr.length() - MAX_LOG)
            for (i in start until arr.length()) kept.put(arr.getJSONObject(i))
            p[K.LOG] = kept.toString()
        }
    }

    suspend fun clearLog() {
        app.dataStore.edit { it.remove(K.LOG) }
    }

    private fun readConfig(p: Preferences): QuotaConfig = QuotaConfig(
        enabled = p[K.ENABLED] ?: true,
        startHour = p[K.START_H] ?: 5,
        startMinute = p[K.START_M] ?: 30,
        cutoffHour = p[K.CUTOFF_H] ?: 22,
        cutoffMinute = p[K.CUTOFF_M] ?: 0,
        windowMinutes = p[K.WINDOW_MIN] ?: 300,
        graceSeconds = p[K.GRACE_S] ?: 60,
        model = p[K.MODEL] ?: "gpt-6-astra",
        prompt = p[K.PROMPT] ?: "Reply with exactly OK.",
    )

    private fun readState(p: Preferences): RuntimeState {
        val log = runCatching {
            val arr = JSONArray(p[K.LOG] ?: "[]")
            (0 until arr.length()).map { Event.fromJson(arr.getJSONObject(it)) }.reversed()
        }.getOrDefault(emptyList())
        val cached = runCatching {
            p[K.CACHED]?.let { UsageSnapshot.fromJson(JSONObject(it)) }
        }.getOrNull()
        return RuntimeState(
            nextActivation = p[K.NEXT]?.toLongOrNull()?.let(Instant::ofEpochMilli),
            lastPrime = p[K.LAST_PRIME]?.toLongOrNull()?.let(Instant::ofEpochMilli),
            lastResult = p[K.LAST_RESULT],
            tokensPresent = !p[K.ACCESS].isNullOrEmpty(),
            accountId = p[K.ACCOUNT],
            log = log,
            cached = cached,
        )
    }

    companion object {
        private const val MAX_LOG = 60

        @Volatile
        private var instance: Prefs? = null

        fun get(context: Context): Prefs =
            instance ?: synchronized(this) {
                instance ?: Prefs(context.applicationContext).also { instance = it }
            }
    }
}
