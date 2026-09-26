package com.example

import android.app.Application
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.CountdownTime
import com.example.data.SubscriptionRecord
import com.example.data.SupabaseRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Duration
import java.time.Instant

data class MainUiState(
    val deviceId: String = "",
    val isLoading: Boolean = false,
    val isLocking: Boolean = false,
    val subscription: SubscriptionRecord? = null,
    val isLocked: Boolean = false,
    val countdown: CountdownTime = CountdownTime(),
    val errorMessage: String? = null,
    val isConfigured: Boolean = false,
    val configuredUrl: String = "",
    val showConfigDialog: Boolean = false,
    val lastSyncTime: Instant? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = SupabaseRepository(application)

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    private var countdownJob: Job? = null

    init {
        val resolvedDeviceId = fetchDeviceId()
        val isConfigured = repository.isConfigured()
        val currentUrl = repository.getEffectiveUrl()

        _uiState.update {
            it.copy(
                deviceId = resolvedDeviceId,
                isConfigured = isConfigured,
                configuredUrl = currentUrl
            )
        }

        checkSubscription()
    }

    private fun fetchDeviceId(): String {
        return try {
            val id = Settings.Secure.getString(
                getApplication<Application>().contentResolver,
                Settings.Secure.ANDROID_ID
            )
            if (!id.isNullOrBlank()) id else "SECURE_DEVICE_" + System.currentTimeMillis().toString().takeLast(6)
        } catch (_: Exception) {
            "SECURE_DEVICE_UNKNOWN"
        }
    }

    fun checkSubscription() {
        val deviceId = _uiState.value.deviceId
        val isConfigured = repository.isConfigured()

        _uiState.update {
            it.copy(
                isConfigured = isConfigured,
                configuredUrl = repository.getEffectiveUrl(),
                isLoading = true,
                errorMessage = null
            )
        }

        if (!isConfigured) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isLocked = false,
                    subscription = null
                )
            }
            return
        }

        viewModelScope.launch {
            val result = repository.getSubscription(deviceId)
            result.fold(
                onSuccess = { record ->
                    val isActive = record != null && record.isSubscriptionActive()
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            subscription = record,
                            isLocked = isActive,
                            lastSyncTime = Instant.now(),
                            errorMessage = null
                        )
                    }

                    if (isActive) {
                        startCountdownTicker(record!!)
                    } else {
                        stopCountdownTicker()
                    }
                },
                onFailure = { error ->
                    val userFriendlyMsg = formatErrorMessage(error)
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            errorMessage = userFriendlyMsg
                        )
                    }
                    stopCountdownTicker()
                }
            )
        }
    }

    fun lockForOneYear() {
        val deviceId = _uiState.value.deviceId
        if (!repository.isConfigured()) {
            _uiState.update {
                it.copy(
                    showConfigDialog = true,
                    errorMessage = "Please enter your Supabase URL & Anon Key to lock the device."
                )
            }
            return
        }

        _uiState.update { it.copy(isLocking = true, errorMessage = null) }

        viewModelScope.launch {
            val result = repository.createOneYearSubscription(deviceId)
            result.fold(
                onSuccess = { record ->
                    _uiState.update {
                        it.copy(
                            isLocking = false,
                            subscription = record,
                            isLocked = true,
                            lastSyncTime = Instant.now(),
                            errorMessage = null
                        )
                    }
                    startCountdownTicker(record)
                },
                onFailure = { error ->
                    val userFriendlyMsg = formatErrorMessage(error)
                    _uiState.update {
                        it.copy(
                            isLocking = false,
                            errorMessage = userFriendlyMsg
                        )
                    }
                }
            )
        }
    }

    private fun startCountdownTicker(record: SubscriptionRecord) {
        countdownJob?.cancel()
        val targetInstant = record.getExpirationInstant() ?: return

        countdownJob = viewModelScope.launch {
            while (isActive) {
                val now = Instant.now()
                val duration = Duration.between(now, targetInstant)

                if (duration.isNegative || duration.isZero) {
                    _uiState.update {
                        it.copy(
                            isLocked = false,
                            countdown = CountdownTime(isExpired = true)
                        )
                    }
                    break
                } else {
                    val totalSecs = duration.seconds
                    val days = duration.toDays()
                    val hours = duration.toHours() % 24
                    val minutes = duration.toMinutes() % 60
                    val seconds = duration.seconds % 60

                    _uiState.update {
                        it.copy(
                            countdown = CountdownTime(
                                days = days,
                                hours = hours,
                                minutes = minutes,
                                seconds = seconds,
                                totalSecondsRemaining = totalSecs,
                                isExpired = false
                            )
                        )
                    }
                }

                delay(1000L)
            }
        }
    }

    private fun stopCountdownTicker() {
        countdownJob?.cancel()
        countdownJob = null
    }

    fun openConfigDialog() {
        _uiState.update { it.copy(showConfigDialog = true) }
    }

    fun dismissConfigDialog() {
        _uiState.update { it.copy(showConfigDialog = false) }
    }

    fun saveSupabaseCredentials(url: String, anonKey: String) {
        repository.saveCustomCredentials(url, anonKey)
        _uiState.update {
            it.copy(
                isConfigured = repository.isConfigured(),
                configuredUrl = repository.getEffectiveUrl(),
                showConfigDialog = false
            )
        }
        checkSubscription()
    }

    fun resetToDefaults() {
        repository.clearCustomCredentials()
        _uiState.update {
            it.copy(
                isConfigured = repository.isConfigured(),
                configuredUrl = repository.getEffectiveUrl()
            )
        }
        checkSubscription()
    }

    fun dismissErrorMessage() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private fun formatErrorMessage(error: Throwable): String {
        val msg = error.message ?: "Unknown error"
        return when {
            msg.contains("relation \"subscriptions\" does not exist", ignoreCase = true) ||
            msg.contains("42P01", ignoreCase = true) ->
                "Table 'subscriptions' does not exist yet in your Supabase database. Please create it in the Supabase SQL Editor."

            msg.contains("401", ignoreCase = true) || msg.contains("Invalid API key", ignoreCase = true) ||
            msg.contains("JWT", ignoreCase = true) ->
                "Invalid Supabase Anon Key. Please verify your Project Anon Key in Settings."

            msg.contains("Failed to connect", ignoreCase = true) || msg.contains("UnknownHost", ignoreCase = true) ||
            msg.contains("ConnectException", ignoreCase = true) ->
                "Unable to connect to Supabase. Check your network connection and Supabase URL."

            else -> msg
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopCountdownTicker()
    }
}
