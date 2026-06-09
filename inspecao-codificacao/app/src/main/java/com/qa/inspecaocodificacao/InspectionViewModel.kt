package com.qa.inspecaocodificacao

import android.app.Application
import android.graphics.Bitmap
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qa.inspecaocodificacao.alert.AlarmController
import com.qa.inspecaocodificacao.camera.RoiCropper
import com.qa.inspecaocodificacao.data.MetricsRepository
import com.qa.inspecaocodificacao.data.ShiftMetrics
import com.qa.inspecaocodificacao.domain.AppState
import com.qa.inspecaocodificacao.domain.InspectionConfig
import com.qa.inspecaocodificacao.domain.ItemStateMachine
import com.qa.inspecaocodificacao.ml.AnomalyModel
import com.qa.inspecaocodificacao.ml.BaselineStore
import com.qa.inspecaocodificacao.ml.CalibrationSession
import com.qa.inspecaocodificacao.ml.FeatureExtractor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Orquestrador central: recebe frames do executor do CameraX, roda o
 * pipeline ML e dirige as DUAS máquinas de estado (app e item).
 *
 * Threading: onFrame() chega sempre pela MESMA thread (executor único do
 * ImageAnalysis), então o pipeline ML e a ItemStateMachine não precisam de
 * locks. Apenas a publicação de estado (StateFlow) e a persistência
 * (DataStore via coroutine) cruzam threads — ambas são thread-safe.
 */
class InspectionViewModel(application: Application) : AndroidViewModel(application) {

    val config = InspectionConfig()

    private val featureExtractor = FeatureExtractor(application)
    private val baselineStore = BaselineStore(application)
    private val metricsRepository = MetricsRepository(application)
    val alarmController = AlarmController(application, viewModelScope)

    private val _appState = MutableStateFlow<AppState>(AppState.Idle)
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    val metrics: StateFlow<ShiftMetrics> = metricsRepository.metrics
        .stateIn(viewModelScope, SharingStarted.Eagerly, ShiftMetrics())

    // ---- Estado interno do pipeline (tocado só pela thread de análise) ----
    private var anomalyModel: AnomalyModel? = null
    private var calibrationSession: CalibrationSession? = null
    private var calibrationStartMs = 0L

    private val itemMachine = ItemStateMachine(config, object : ItemStateMachine.Listener {
        override fun onDefectLive(anomalyScore: Float) {
            // Estado C com score acima do threshold: ALARME imediato.
            _appState.value = AppState.Alarm(anomalyScore)
            alarmController.start()
        }

        override fun onItemCompleted(peakScore: Float, isDefect: Boolean) {
            // Estado D confirmado: ponto ÚNICO de contagem (anti-supercontagem)
            // e AUTO-RESET do alarme.
            viewModelScope.launch { metricsRepository.registerItem(isDefect) }
            if (_appState.value is AppState.Alarm) {
                alarmController.stop()
                _appState.value = AppState.Monitoring
            }
        }
    })

    init {
        // Recuperação pós-reinício: se há baseline salva, volta direto a monitorar.
        baselineStore.load()?.let { model ->
            anomalyModel = model
            itemMachine.setDefectThreshold(model.defectThreshold)
            _appState.value = AppState.Monitoring
        }
    }

    // ------------------------- Ações do operador -------------------------

    /** Confirmada pelo operador no diálogo ("Isso resetará o modelo e as métricas"). */
    fun startCalibration() {
        alarmController.stop()
        anomalyModel = null
        baselineStore.clear()
        itemMachine.reset()
        calibrationSession = CalibrationSession(config)
        calibrationStartMs = SystemClock.elapsedRealtime()
        viewModelScope.launch { metricsRepository.resetShift() }
        _appState.value = AppState.Calibrating(0f, calibrationSession!!.phaseFor(0))
    }

    /** "Zerar Turno" do modo QA Admin: zera métricas sem perder a baseline. */
    fun resetShiftMetrics() {
        viewModelScope.launch { metricsRepository.resetShift() }
    }

    // --------------------- Pipeline por frame (bg thread) ---------------------

    /** Chamada pelo FrameAnalyzer no executor de fundo do CameraX. */
    fun onFrame(frame: Bitmap) {
        val state = _appState.value
        if (state is AppState.Idle) return // economiza CPU/bateria sem baseline

        val roi = RoiCropper.crop(frame, config)
        val embedding = featureExtractor.extract(roi)
        roi.recycle()

        when (state) {
            is AppState.Calibrating -> onCalibrationFrame(embedding)
            is AppState.Monitoring, is AppState.Alarm -> onMonitoringFrame(embedding)
            is AppState.Idle -> Unit
        }
    }

    private fun onCalibrationFrame(embedding: FloatArray) {
        val session = calibrationSession ?: return
        val elapsed = SystemClock.elapsedRealtime() - calibrationStartMs

        if (elapsed < config.calibrationDurationMs) {
            session.addFrame(embedding, elapsed)
            val progress = elapsed.toFloat() / config.calibrationDurationMs
            _appState.value = AppState.Calibrating(progress, session.phaseFor(elapsed))
            return
        }

        // Tempo esgotado: consolida a baseline e entra em produção.
        val model = session.build()
        calibrationSession = null
        if (model == null) {
            // Linha parada durante a calibração: volta a IDLE para refazer.
            _appState.value = AppState.Idle
            return
        }
        anomalyModel = model
        baselineStore.save(model)
        itemMachine.setDefectThreshold(model.defectThreshold)
        itemMachine.reset()
        _appState.value = AppState.Monitoring
    }

    private fun onMonitoringFrame(embedding: FloatArray) {
        val model = anomalyModel ?: return
        itemMachine.onFrame(
            presenceScore = model.presenceScore(embedding),
            anomalyScore = model.anomalyScore(embedding),
        )
    }

    override fun onCleared() {
        alarmController.release()
        featureExtractor.close()
    }
}
