package com.qa.inspecaocodificacao.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.qa.inspecaocodificacao.domain.CellMask
import com.qa.inspecaocodificacao.domain.RoiFractions
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Ajustes de campo persistidos — sobrevivem a reinício:
 *  - ROI (janela de medição) + MÁSCARA de células ignoradas + ZOOM da câmera,
 *    salvos juntos no modo de ajuste (alterá-los invalida os treinos);
 *  - sensibilidade de presença: multiplicador sobre os thresholds, sem retreino;
 *  - overlay de diagnóstico.
 */
data class InspectionSettings(
    val roi: RoiFractions = RoiFractions(),
    val maskString: String = "",
    val zoomRatio: Float = 1f,
    val presenceSensitivity: Float = 1f, // 0.25..4; maior = dispara mais fácil
    val showDiagnostics: Boolean = true,
)

private val Context.settingsStore by preferencesDataStore(name = "inspection_settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val ROI_LEFT = floatPreferencesKey("roi_left")
        val ROI_TOP = floatPreferencesKey("roi_top")
        val ROI_RIGHT = floatPreferencesKey("roi_right")
        val ROI_BOTTOM = floatPreferencesKey("roi_bottom")
        val MASK = stringPreferencesKey("cell_mask")
        val ZOOM = floatPreferencesKey("zoom_ratio")
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
            maskString = prefs[Keys.MASK]?.takeIf { it.length == CellMask.CELLS } ?: "",
            zoomRatio = (prefs[Keys.ZOOM] ?: 1f).coerceAtLeast(1f),
            presenceSensitivity = (prefs[Keys.SENSITIVITY] ?: 1f).coerceIn(0.25f, 4f),
            showDiagnostics = prefs[Keys.SHOW_DIAGNOSTICS] ?: true,
        )
    }

    /** ROI + máscara + zoom salvos atomicamente (ajuste de campo). */
    suspend fun setMeasurementSetup(roi: RoiFractions, maskString: String, zoomRatio: Float) {
        val r = roi.clamped()
        context.settingsStore.edit { prefs ->
            prefs[Keys.ROI_LEFT] = r.left
            prefs[Keys.ROI_TOP] = r.top
            prefs[Keys.ROI_RIGHT] = r.right
            prefs[Keys.ROI_BOTTOM] = r.bottom
            prefs[Keys.MASK] = maskString
            prefs[Keys.ZOOM] = zoomRatio.coerceAtLeast(1f)
        }
    }

    suspend fun setPresenceSensitivity(value: Float) {
        context.settingsStore.edit { it[Keys.SENSITIVITY] = value.coerceIn(0.25f, 4f) }
    }

    suspend fun setShowDiagnostics(value: Boolean) {
        context.settingsStore.edit { it[Keys.SHOW_DIAGNOSTICS] = value }
    }
}
