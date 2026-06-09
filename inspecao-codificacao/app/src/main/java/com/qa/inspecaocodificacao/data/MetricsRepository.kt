package com.qa.inspecaocodificacao.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Métricas do turno persistidas em DataStore: sobrevivem a reinício
 * acidental do app ou queda de energia do tablet.
 */
data class ShiftMetrics(
    val totalInspected: Int = 0,
    val totalDefects: Int = 0,
    val shiftStartEpochMs: Long = 0L,
) {
    val defectRatePercent: Float
        get() = if (totalInspected == 0) 0f else totalDefects * 100f / totalInspected
}

private val Context.dataStore by preferencesDataStore(name = "shift_metrics")

class MetricsRepository(private val context: Context) {

    private object Keys {
        val TOTAL = intPreferencesKey("total_inspected")
        val DEFECTS = intPreferencesKey("total_defects")
        val SHIFT_START = longPreferencesKey("shift_start")
    }

    val metrics: Flow<ShiftMetrics> = context.dataStore.data.map { prefs ->
        ShiftMetrics(
            totalInspected = prefs[Keys.TOTAL] ?: 0,
            totalDefects = prefs[Keys.DEFECTS] ?: 0,
            shiftStartEpochMs = prefs[Keys.SHIFT_START] ?: 0L,
        )
    }

    /** Chamada UMA vez por garrafa, na transição Saindo -> Vazio. */
    suspend fun registerItem(isDefect: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[Keys.TOTAL] = (prefs[Keys.TOTAL] ?: 0) + 1
            if (isDefect) {
                prefs[Keys.DEFECTS] = (prefs[Keys.DEFECTS] ?: 0) + 1
            }
        }
    }

    /** "Zerar Turno" — só acessível no modo QA Admin (toque longo no logo). */
    suspend fun resetShift() {
        context.dataStore.edit { prefs ->
            prefs[Keys.TOTAL] = 0
            prefs[Keys.DEFECTS] = 0
            prefs[Keys.SHIFT_START] = System.currentTimeMillis()
        }
    }
}
