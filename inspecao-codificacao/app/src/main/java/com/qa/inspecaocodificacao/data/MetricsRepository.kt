package com.qa.inspecaocodificacao.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Métricas do turno.
 *
 * OTIMIZAÇÃO 18k gph: a 5 garrafas/s, um edit do DataStore por item seriam
 * 5 reescritas+fsync por segundo, o turno inteiro — desgaste de flash e
 * jank de I/O. Os contadores vivem em um StateFlow em memória (incremento
 * lock-free na thread de análise) e são persistidos em LOTE a cada
 * flushIntervalMs. Janela máxima de perda em queda de energia: ~1,5 s
 * (≈7 garrafas em 18.000/h) — irrelevante para a taxa de defeito do turno.
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

class MetricsRepository(
    private val context: Context,
    scope: CoroutineScope,
    private val flushIntervalMs: Long,
) {

    private object Keys {
        val TOTAL = intPreferencesKey("total_inspected")
        val DEFECTS = intPreferencesKey("total_defects")
        val SHIFT_START = longPreferencesKey("shift_start")
    }

    private val _metrics = MutableStateFlow(ShiftMetrics())
    val metrics: StateFlow<ShiftMetrics> = _metrics.asStateFlow()

    @Volatile
    private var dirty = false

    init {
        scope.launch {
            // Carga inicial somada ao que já foi registrado em memória
            // (a análise pode começar antes da leitura do disco terminar).
            val prefs = context.dataStore.data.first()
            val persisted = ShiftMetrics(
                totalInspected = prefs[Keys.TOTAL] ?: 0,
                totalDefects = prefs[Keys.DEFECTS] ?: 0,
                shiftStartEpochMs = prefs[Keys.SHIFT_START] ?: 0L,
            )
            _metrics.update { inMemory ->
                ShiftMetrics(
                    totalInspected = persisted.totalInspected + inMemory.totalInspected,
                    totalDefects = persisted.totalDefects + inMemory.totalDefects,
                    shiftStartEpochMs = persisted.shiftStartEpochMs,
                )
            }

            while (isActive) {
                delay(flushIntervalMs)
                if (dirty) flush()
            }
        }
    }

    /**
     * Chamada UMA vez por garrafa, na transição Saindo -> Vazio.
     * Síncrona e lock-free: segura para a thread de análise a 30 fps.
     */
    fun registerItem(isDefect: Boolean) {
        _metrics.update {
            it.copy(
                totalInspected = it.totalInspected + 1,
                totalDefects = it.totalDefects + if (isDefect) 1 else 0,
            )
        }
        dirty = true
    }

    /** "Zerar Turno" — só acessível no modo QA Admin (toque longo no logo). */
    suspend fun resetShift() {
        _metrics.value = ShiftMetrics(shiftStartEpochMs = System.currentTimeMillis())
        flush()
    }

    suspend fun flush() {
        dirty = false
        val snapshot = _metrics.value
        context.dataStore.edit { prefs ->
            prefs[Keys.TOTAL] = snapshot.totalInspected
            prefs[Keys.DEFECTS] = snapshot.totalDefects
            prefs[Keys.SHIFT_START] = snapshot.shiftStartEpochMs
        }
    }
}
