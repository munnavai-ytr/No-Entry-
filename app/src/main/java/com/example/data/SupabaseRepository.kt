package com.example.data

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.postgrest.from
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.temporal.ChronoUnit

class SupabaseRepository(private val context: Context) {

    private val prefs = context.getSharedPreferences("supabase_config", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "SupabaseRepository"
        private const val PREF_URL = "custom_supabase_url"
        private const val PREF_KEY = "custom_supabase_anon_key"
    }

    fun getEffectiveUrl(): String {
        val custom = prefs.getString(PREF_URL, null)?.trim()
        if (!custom.isNullOrEmpty()) return custom

        return try {
            BuildConfig.SUPABASE_URL.trim()
        } catch (_: Exception) {
            ""
        }
    }

    fun getEffectiveAnonKey(): String {
        val custom = prefs.getString(PREF_KEY, null)?.trim()
        if (!custom.isNullOrEmpty()) return custom

        return try {
            BuildConfig.SUPABASE_ANON_KEY.trim()
        } catch (_: Exception) {
            ""
        }
    }

    fun saveCustomCredentials(url: String, anonKey: String) {
        prefs.edit()
            .putString(PREF_URL, url.trim())
            .putString(PREF_KEY, anonKey.trim())
            .apply()
        cachedClient = null
    }

    fun clearCustomCredentials() {
        prefs.edit()
            .remove(PREF_URL)
            .remove(PREF_KEY)
            .apply()
        cachedClient = null
    }

    fun isConfigured(): Boolean {
        val url = getEffectiveUrl()
        val key = getEffectiveAnonKey()
        return url.isNotBlank() &&
                !url.contains("your-project.supabase.co") &&
                url.startsWith("https://") &&
                key.isNotBlank() &&
                !key.contains("your-anon-key-here")
    }

    private var cachedClient: SupabaseClient? = null
    private var lastUrl: String? = null
    private var lastKey: String? = null

    @Synchronized
    private fun getClient(): SupabaseClient {
        val url = getEffectiveUrl()
        val key = getEffectiveAnonKey()

        if (cachedClient != null && lastUrl == url && lastKey == key) {
            return cachedClient!!
        }

        val client = createSupabaseClient(
            supabaseUrl = url.ifBlank { "https://placeholder.supabase.co" },
            supabaseKey = key.ifBlank { "placeholder-key" }
        ) {
            install(Postgrest)
        }
        cachedClient = client
        lastUrl = url
        lastKey = key
        return client
    }

    suspend fun getSubscription(deviceId: String): Result<SubscriptionRecord?> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(
                IllegalStateException("Supabase is not configured. Please supply your Supabase Project URL and Anon Key.")
            )
        }

        try {
            val client = getClient()
            val list = client.from("subscriptions").select {
                filter {
                    eq("device_id", deviceId)
                }
            }.decodeList<SubscriptionRecord>()

            Result.success(list.firstOrNull())
        } catch (e: Exception) {
            Log.e(TAG, "Error querying subscriptions table: ${e.message}", e)
            Result.failure(e)
        }
    }

    suspend fun createOneYearSubscription(deviceId: String): Result<SubscriptionRecord> = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Result.failure(
                IllegalStateException("Supabase is not configured. Please supply your Supabase Project URL and Anon Key.")
            )
        }

        try {
            val client = getClient()
            val expirationInstant = Instant.now().plus(365, ChronoUnit.DAYS)
            val expiresAtIso = expirationInstant.toString()

            val insertData = SubscriptionInsert(
                deviceId = deviceId,
                expiresAt = expiresAtIso
            )

            // Insert new 1-year subscription
            client.from("subscriptions").insert(insertData)

            // Return active subscription record
            val created = SubscriptionRecord(
                deviceId = deviceId,
                expiresAt = expiresAtIso,
                createdAt = Instant.now().toString()
            )
            Result.success(created)
        } catch (e: Exception) {
            Log.e(TAG, "Error inserting subscription: ${e.message}", e)
            Result.failure(e)
        }
    }
}
