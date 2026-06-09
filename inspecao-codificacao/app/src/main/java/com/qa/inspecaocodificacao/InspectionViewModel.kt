package com.qa.inspecaocodificacao

import android.app.Application
import android.graphics.Rect
import android.os.SystemClock
import androidx.camera.core.ImageProxy
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.qa.inspecaocodificacao.alert.AlarmController
import com.qa.inspecaocodificacao.camera.LumaGrid
import com.qa.inspecaocodificacao.camera.RoiCropper
import com.qa.inspecaocodificacao.camera.RoiMapper
import com.qa.inspecaocodificacao.data.MetricsRepository
import com.qa.inspecaocodificacao.data.ShiftMetrics
import com.qa.inspecaocodificacao.domain.AppState
import com.qa.inspecaocodificacao.domain.CalibrationPhase
import com.qa.inspecaocodificacao.domain.InspectionConfig
import com.qa.inspecaocodificacao.domain.ItemStateMachine
import com.qa.inspecaocodificacao.ml.BaselineStore
import com.qa.inspecaocodificacao.ml.CalibrationSession
import com.qa.inspecaocodificacao.ml.FeatureExtractor
import com.qa.inspecaocodificacao.ml.InspectionModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Orquestrador central, dimensionado para 18.000 garrafas/h no Galaxy M31.
 *
 * Pipeline por frame (30 fps, thread única do ImageAnalysis):
 *   ESTÁGIO 1 (todo frame, <0,5 ms): grade de luma da ROI direto do buffer
 *     -> score de presença -> dirige a ItemStateMachine (contagem).
 *   ESTÁGIO 2 (só com garrafa presente, ~10-15 ms): crop da ROI -> TFLite
 *     -> anomaly score. A 5 garrafas/s isso significa ~15 inferências/s
 *     em vez de 30 — metade do custo, e a contagem nunca perde frame.
 */
class InspectionViewModel(application: Application) : AndroidViewModel(application) {

    val config = InspectionConfig()

    private val featureExtractor = FeatureExtractor(application, config.useGpuDelegate)
    private val baselineStore = BaselineStore(application)
    private val metricsRepository =
        MetricsRepository(application, viewModelScope, config.metricsFlushIntervalMs)
    val alarmController = AlarmController(application, viewModelScope, config.useTorchOnAlarm)

    /** Lock de AE/AWB (Camera2Interop), injetado pela Activity após o bind. */
    @Volatile var exposureLocker: ((Boolean) -> Unit)? = null

    private val _appState = MutableStateFlow<AppState>(AppState.Idle)
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    val metrics: StateFlow<ShiftMetrics> = metricsRepository.metrics

    // ---- Estado interno do pipeline (tocado só pela thread de análise) ----
    private var model: InspectionModel? = null
    private var calibrationSession: CalibrationSession? = null
    private var calibrationStartMs = 0L

    private var roiRect: Rect? = null
    private val lumaGrid = FloatArray(LumaGrid.CELLS) // reutilizada por frame
    private val roiCropper = RoiCropper()

    private var alarmStopJob: Job? = null

    private val itemMachine = ItemStateMachine(config, object : ItemStateMachine.Listener {
        override fun onDefectLive(anomalyScore: Float) {
            // Estado C com score acima do threshold: ALARME imediato.
            alarmStopJob?.cancel()
            alarmStopJob = null
            _appState.value = AppState.Alarm(anomalyScore)
            alarmController.start()
        }

        override fun onItemCompleted(peakScore: Float, isDefect: Boolean) {
            // Estado D confirmado: ponto ÚNICO de contagem (anti-supercontagem).
            metricsRepository.registerItem(isDefect)

            // AUTO-RESET com retenção mínima: a 5 garrafas/s o item sai da
            // ROI em ~150 ms — um alarme que parasse aqui seria imperceptível.
            // O defeito JÁ FOI contado; o alarme segue por alarmMinDurationMs
            // para o operador reagir. Itens bons saindo não estendem o tempo;
            // um novo defeito cancela o desligamento e re-arma.
            if (_appState.value is AppState.Alarm && alarmStopJob == null) {
                alarmStopJob = viewModelScope.launch {
                    delay(config.alarmMinDurationMs)
                    alarmController.stop()
                    _appState.value = AppState.Monitoring
                    alarmStopJob = null
                }
            }
        }
    })

    init {
        // Recuperação pós-reinício: se há baseline salva, volta direto a monitorar.
        baselineStore.load()?.let { loaded ->
            model = loaded
            applyModel(loaded)
            _appState.value = AppState.Monitoring
            viewModelScope.launch { exposureLocker?.invoke(true) }
        }
    }

    private fun applyModel(m: InspectionModel) {
        itemMachine.configure(
            presenceEnter = m.presenceEnterThreshold,
            presenceExit = m.presenceExitThreshold,
            defect = m.defectThreshold,
        )
        itemMachine.reset()
    }

    // ------------------------- Ações do operador -------------------------

    /** Confirmada pelo operador no diálogo ("Isso resetará o modelo e as métricas"). */
    fun startCalibration() {
        alarmStopJob?.cancel()
        alarmStopJob = null
        alarmController.stop()
        model = null
        baselineStore.clear()
        itemMachine.reset()
        calibrationSession = CalibrationSession(config)
        calibrationStartMs = SystemClock.elapsedRealtime()
        viewModelScope.launch { metricsRepository.resetShift() }

        // Destrava AE/AWB, deixa assentar na cena atual e trava de novo:
        // exposição fixa = baseline estável o turno inteiro (câmera fixa).
        exposureLocker?.invoke(false)
        viewModelScope.launch {
            delay(config.aeSettleMs)
            exposureLocker?.invoke(true)
        }

        _appState.value = AppState.Calibrating(0f, CalibrationPhase.EMPTY_BELT)
    }

    /** "Zerar Turno" do modo QA Admin: zera métricas sem perder a baseline. */
    fun resetShiftMetrics() {
        viewModelScope.launch { metricsRepository.resetShift() }
    }

    // --------------------- Pipeline por frame (bg thread) ---------------------

    /** Chamada pelo FrameAnalyzer no executor de fundo do CameraX. */
    fun onFrame(proxy: ImageProxy) {
        val state = _appState.value
        if (state is AppState.Idle) return // economiza CPU/bateria sem baseline

        // ROI fixa: o Rect em coordenadas do buffer é calculado uma única vez.
        val roi = roiRect ?: RoiMapper.bufferRect(config, proxy).also { roiRect = it }

        // ESTÁGIO 1: presença por luma, em todo frame.
        LumaGrid.sample(proxy, roi, lumaGrid)

        when (state) {
            is AppState.Calibrating -> onCalibrationFrame(proxy, roi)
            is AppState.Monitoring, is AppState.Alarm -> {
                val m = model ?: return
                itemMachine.onFrame(
                    presenceScore = m.presenceScore(lumaGrid),
                    // ESTÁGIO 2 sob demanda: TFLite só roda se a máquina
                    // estiver avaliando uma garrafa.
                    anomalyScore = { m.anomalyScore(featureExtractor.extract(roiCropper.crop(proxy, roi))) },
                )
            }
            is AppState.Idle -> Unit
        }
    }

    private fun onCalibrationFrame(proxy: ImageProxy, roi: Rect) {
        val session = calibrationSession ?: return
        val elapsed = SystemClock.elapsedRealtime() - calibrationStartMs

        if (elapsed < config.calibrationDurationMs) {
            when (session.phaseFor(elapsed)) {
                CalibrationPhase.EMPTY_BELT ->
                    // Descarta o início: AE/AWB ainda assentando antes do lock.
                    if (elapsed >= config.aeSettleMs) session.addEmptyGrid(lumaGrid)

                CalibrationPhase.PRODUCT -> {
                    if (!session.ensureBackgroundReady()) {
                        abortCalibration()
                        return
                    }
                    // Mesmo gate do estágio 1: só frames COM garrafa entram
                    // na baseline (o fundo não contamina a assinatura).
                    if (session.presenceScore(lumaGrid) > session.presenceEnterThreshold) {
                        session.addProductEmbedding(
                            featureExtractor.extract(roiCropper.crop(proxy, roi))
                        )
                    }
                }
            }
            _appState.value = AppState.Calibrating(
                progress = elapsed.toFloat() / config.calibrationDurationMs,
                phase = session.phaseFor(elapsed),
            )
            return
        }

        // Tempo esgotado: consolida a baseline e entra em produção.
        val built = session.build()
        calibrationSession = null
        if (built == null) {
            // Linha parada durante a calibração: volta a IDLE para refazer.
            abortCalibration()
            return
        }
        model = built
        baselineStore.save(built)
        applyModel(built)
        _appState.value = AppState.Monitoring
    }

    private fun abortCalibration() {
        calibrationSession = null
        _appState.value = AppState.Idle
    }

    override fun onCleared() {
        alarmController.release()
        featureExtractor.close()
        // Última gravação dos contadores (escrita pequena; viewModelScope já cancelado).
        runBlocking { metricsRepository.flush() }
    }
}
