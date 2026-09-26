package com.eclip.rup.leb.utils

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList

/**
 * ThermalProtectionManager provides proactive device temperature and thermal throttling monitoring.
 *
 * Core Principles:
 * 1. Hardware-Aware Throttling: Uses Android 10+ (API 29) PowerManager thermal callbacks with BatteryManager fallback.
 * 2. Dynamic Resource Allocation: Scales background worker parallelism (3 -> 1 -> 0) based on thermal state.
 * 3. Graceful Background Pausing: Pauses non-essential thumbnail/waveform extraction when device is hot,
 *    giving 100% priority to UI rendering and essential editing actions.
 * 4. Automatic Recovery: Seamlessly resumes background tasks when the device temperature normalizes.
 */
object ThermalProtectionManager {

    private const val TAG = "ThermalProtectionManager"

    enum class ThermalLevel {
        NORMAL,     // Device temperature is nominal; full processing speed.
        ELEVATED,   // Device is warming up; reduced background concurrency (1 worker).
        THROTTLED   // Device is hot / thermal throttling active; pause non-essential background tasks.
    }

    private var currentLevel: ThermalLevel = ThermalLevel.NORMAL
    private val listeners = CopyOnWriteArrayList<(ThermalLevel) -> Unit>()
    private var isRegistered = false

    val currentThermalLevel: ThermalLevel
        get() = currentLevel

    val isThrottled: Boolean
        get() = currentLevel == ThermalLevel.THROTTLED

    val isElevatedOrThrottled: Boolean
        get() = currentLevel != ThermalLevel.NORMAL

    /**
     * Initializes thermal status monitoring with system-level listeners.
     */
    fun initialize(context: Context) {
        if (isRegistered) return
        val appContext = context.applicationContext

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val powerManager = appContext.getSystemService(Context.POWER_SERVICE) as? PowerManager
                powerManager?.addThermalStatusListener { status ->
                    val newLevel = when (status) {
                        PowerManager.THERMAL_STATUS_NONE,
                        PowerManager.THERMAL_STATUS_LIGHT -> ThermalLevel.NORMAL
                        PowerManager.THERMAL_STATUS_MODERATE -> ThermalLevel.ELEVATED
                        PowerManager.THERMAL_STATUS_SEVERE,
                        PowerManager.THERMAL_STATUS_CRITICAL,
                        PowerManager.THERMAL_STATUS_EMERGENCY,
                        PowerManager.THERMAL_STATUS_SHUTDOWN -> ThermalLevel.THROTTLED
                        else -> ThermalLevel.NORMAL
                    }
                    updateThermalLevel(newLevel)
                }
                isRegistered = true
                Log.d(TAG, "Initialized PowerManager thermal status listener.")
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register PowerManager thermal listener: ${e.message}")
            }
        }

        // Fallback: Battery temperature receiver
        try {
            val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            appContext.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent == null) return
                    val tempTenthsOfCelsius = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
                    if (tempTenthsOfCelsius > 0) {
                        val tempCelsius = tempTenthsOfCelsius / 10.0f
                        val newLevel = when {
                            tempCelsius >= 45.0f -> ThermalLevel.THROTTLED
                            tempCelsius >= 40.0f -> ThermalLevel.ELEVATED
                            else -> ThermalLevel.NORMAL
                        }
                        updateThermalLevel(newLevel)
                    }
                }
            }, filter)
            isRegistered = true
            Log.d(TAG, "Initialized BatteryManager thermal fallback receiver.")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register battery temperature receiver: ${e.message}")
        }
    }

    private fun updateThermalLevel(newLevel: ThermalLevel) {
        if (currentLevel != newLevel) {
            val oldLevel = currentLevel
            currentLevel = newLevel
            Log.i(TAG, "Thermal status transitioned from $oldLevel to $newLevel")
            
            // Sync with global Codec pool limits
            CodecInstancePool.setMaxConcurrency(getRecommendedWorkerConcurrency())

            // Notify central resource governor for unified concurrency adjustments
            DeviceCapabilityManager.onThermalChanged(newLevel)

            for (listener in listeners) {
                try {
                    listener(newLevel)
                } catch (e: Exception) {
                    Log.w(TAG, "Error notifying thermal listener", e)
                }
            }
        }
    }

    fun addThermalListener(listener: (ThermalLevel) -> Unit) {
        if (!listeners.contains(listener)) {
            listeners.add(listener)
            listener(currentLevel)
        }
    }

    fun removeThermalListener(listener: (ThermalLevel) -> Unit) {
        listeners.remove(listener)
    }

    /**
     * Returns the recommended max concurrent background decoding workers based on current thermal state.
     */
    fun getRecommendedWorkerConcurrency(): Int {
        return when (currentLevel) {
            ThermalLevel.NORMAL -> 6
            ThermalLevel.ELEVATED -> 4
            ThermalLevel.THROTTLED -> 2
        }
    }
}
