package com.qa.inspecaocodificacao.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.qa.inspecaocodificacao.domain.RoiFractions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Ajustes de campo persistidos — sobrevivem a reinício:
 *  - ROI (janela de medição): ajustada pelo usuário para cada formato de garrafa;
 *  - sensibilidade de presença: multiplicador sobre os thresholds auto-calibrados
 *    (permite corrigir contagem em campo SEM retreinar o fundo);
 *  - overlay de diagnóstico: scores ao vivo para o comissionamento.
 */
data class InspectionSettings(
    val roi: RoiFractions = RoiFractions(),
    val presenceSensitivity: Float = 1f, // 0.25..4; maior = dispara mais fácil
    val showDiagnostics: Boolean = true, // ligado por padrão até o piloto estabilizar
)

private val Context.settingsStore by preferencesDataStore(name = "inspection_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val ROI_LEFT = floatPreferencesKey("roi_left")
        val ROI_TOP = floatPreferencesKey("roi_top")
        val ROI_RIGHT = floatPreferencesKey("roi_right")
        val ROI_BOTTOM = floatPreferencesKey("roi_bottom")
        val SENSITIVITY = floatPreferencesKey("presence_sensitivity")
        val SHOW_DIAGNOSTICS = booleanPreferencesKey("show_diagnostics")
    }

    val settings: Flow<InspectionSettings> = context.settingsStore.data.map { prefs ->
        val default = RoiFractions()
        InspectionSettings(
            roi = RoiFractions(
                left = prefs[Keys.ROI_LEFT] ?: default.left,
                top = prefs[Keys.ROI_TOP] ?: default.top,
                right = prefs[Keys.ROI_RIGHT] ?: default.right,
                bottom = prefs[Keys.ROI_BOTTOM] ?: default.bottom,
            ).clamped(),
            presenceSensitivity = (prefs[Keys.SENSITIVITY] ?: 1f).coerceIn(0.25f, 4f),
            showDiagnostics = prefs[Keys.SHOW_DIAGNOSTICS] ?: true,
        )
    }

    suspend fun setRoi(roi: RoiFractions) {
        val r = roi.clamped()
        context.settingsStore.edit { prefs ->
            prefs[Keys.ROI_LEFT] = r.left
            prefs[Keys.ROI_TOP] = r.top
            prefs[Keys.ROI_RIGHT] = r.right
            prefs[Keys.ROI_BOTTOM] = r.bottom
        }
    }

    suspend fun setPresenceSensitivity(value: Float) {
        context.settingsStore.edit { it[Keys.SENSITIVITY] = value.coerceIn(0.25f, 4f) }
    }

    suspend fun setShowDiagnostics(value: Boolean) {
        context.settingsStore.edit { it[Keys.SHOW_DIAGNOSTICS] = value }
    }
}
