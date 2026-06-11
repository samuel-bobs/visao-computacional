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
import com.qa.inspecaocodificacao.data.InspectionSettings
import com.qa.inspecaocodificacao.data.MetricsRepository
import com.qa.inspecaocodificacao.data.SettingsRepository
import com.qa.inspecaocodificacao.data.ShiftMetrics
import com.qa.inspecaocodificacao.domain.AppState
import com.qa.inspecaocodificacao.domain.InspectionConfig
import com.qa.inspecaocodificacao.domain.ItemStateMachine
import com.qa.inspecaocodificacao.domain.RoiFractions
import com.qa.inspecaocodificacao.domain.TrainingStatus
import com.qa.inspecaocodificacao.ml.BackgroundModel
import com.qa.inspecaocodificacao.ml.BackgroundTrainer
import com.qa.inspecaocodificacao.ml.BaselineStore
import com.qa.inspecaocodificacao.ml.FeatureExtractor
import com.qa.inspecaocodificacao.ml.ProductModel
import com.qa.inspecaocodificacao.ml.ProductTrainer
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/** Telemetria ao vivo para o comissionamento (overlay de diagnóstico). */
data class PipelineDiagnostics(
    val presenceScore: Float = 0f,
    val lastAnomalyScore: Float = 0f,
    val itemState: String = "—",
    val presenceEnter: Float = 0f,
    val presenceExit: Float = 0f,
    val defectThreshold: Float = 0f,
    val fps: Int = 0,
)

/**
 * Orquestrador central. Mudanças do feedback de campo:
 *  - Treino de FUNDO e de PADRÃO em botões/estados independentes;
 *  - Falhas de treino geram mensagem visível (antes o app voltava a IDLE
 *    em silêncio e o operador achava que estava monitorando);
 *  - ROI ajustável em campo (persistida; alterá-la invalida os treinos);
 *  - Sensibilidade de presença ajustável sem retreino;
 *  - Diagnóstico ao vivo: presença, anomalia, estado do item, fps.
 */
class InspectionViewModel(application: Application) : AndroidViewModel(application) {

    val config = InspectionConfig()

    private val featureExtractor = FeatureExtractor(application, config.useGpuDelegate)
    private val baselineStore = BaselineStore(application)
    private val settingsRepository = SettingsRepository(application)
    private val metricsRepository =
        MetricsRepository(application, viewModelScope, config.metricsFlushIntervalMs)
    val alarmController = AlarmController(application, viewModelScope, config.useTorchOnAlarm)

    /** Lock de AE/AWB (Camera2Interop), injetado pela Activity após o bind. */
    @Volatile var exposureLocker: ((Boolean) -> Unit)? = null

    private val _appState = MutableStateFlow<AppState>(AppState.Idle)
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    private val _trainingStatus = MutableStateFlow(TrainingStatus())
    val trainingStatus: StateFlow<TrainingStatus> = _trainingStatus.asStateFlow()

    /** Mensagens operacionais (falha de treino, ROI salva...). */
    private val _uiMessage = MutableStateFlow<String?>(null)
    val uiMessage: StateFlow<String?> = _uiMessage.asStateFlow()

    private val _diagnostics = MutableStateFlow(PipelineDiagnostics())
    val diagnostics: StateFlow<PipelineDiagnostics> = _diagnostics.asStateFlow()

    val metrics: StateFlow<ShiftMetrics> = metricsRepository.metrics
    val settings: StateFlow<InspectionSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, InspectionSettings())

    // ---- Estado interno do pipeline (tocado só pela thread de análise) ----
    @Volatile private var backgroundModel: BackgroundModel? = null
    @Volatile private var productModel: ProductModel? = null

    private var backgroundTrainer: BackgroundTrainer? = null
    private var productTrainer: ProductTrainer? = null
    private var trainingStartMs = 0L

    private var cachedRoi: RoiFractions? = null
    private var cachedRect: Rect? = null
    private val lumaGrid = FloatArray(LumaGrid.CELLS) // reutilizada por frame
    private val roiCropper = RoiCropper()

    private var alarmStopJob: Job? = null

    // Telemetria
    private var lastAnomaly = 0f
    private var lastDiagPublishMs = 0L
    private var fpsCounter = 0
    private var fpsWindowStartMs = 0L
    private var measuredFps = 0

    private val itemMachine = ItemStateMachine(config, object : ItemStateMachine.Listener {
        override fun onDefectLive(anomalyScore: Float) {
            alarmStopJob?.cancel()
            alarmStopJob = null
            _appState.value = AppState.Alarm(anomalyScore)
            alarmController.start()
        }

        override fun onItemCompleted(peakScore: Float, isDefect: Boolean) {
            // Estado D confirmado: ponto ÚNICO de contagem (anti-supercontagem).
            metricsRepository.registerItem(isDefect)

            // Auto-reset com retenção mínima (defeito já contado).
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
        // Recuperação pós-reinício: retoma o que já foi treinado.
        backgroundModel = baselineStore.loadBackground()
        productModel = baselineStore.loadProduct()
        publishTrainingStatus()
        if (backgroundModel != null && productModel != null) {
            _appState.value = AppState.Monitoring
        }

        // Sensibilidade/ROI mudam em runtime: reconfigura a máquina por item.
        viewModelScope.launch {
            settings.collect { reconfigureMachine() }
        }
    }

    private fun publishTrainingStatus() {
        _trainingStatus.value = TrainingStatus(
            hasBackground = backgroundModel != null,
            hasProduct = productModel != null,
        )
    }

    /** Thresholds efetivos = auto-calibrados / sensibilidade do usuário. */
    private fun effectiveEnter(bg: BackgroundModel): Float =
        bg.presenceEnterThreshold / settings.value.presenceSensitivity

    private fun effectiveExit(bg: BackgroundModel): Float =
        bg.presenceExitThreshold / settings.value.presenceSensitivity

    private fun reconfigureMachine() {
        val bg = backgroundModel ?: return
        itemMachine.configure(
            presenceEnter = effectiveEnter(bg),
            presenceExit = effectiveExit(bg),
            defect = productModel?.defectThreshold ?: Float.MAX_VALUE,
        )
    }

    // ------------------------- Ações do operador -------------------------

    /** Botão "TREINAR FUNDO" (ROI vazia). Confirmado em diálogo. */
    fun startBackgroundTraining() {
        stopAlarmAndReset()
        // Fundo novo invalida o padrão (thresholds e gate mudam).
        productModel = null
        baselineStore.clearProduct()
        backgroundModel = null
        publishTrainingStatus()

        backgroundTrainer = BackgroundTrainer(config)
        trainingStartMs = SystemClock.elapsedRealtime()

        // Destrava AE/AWB, deixa assentar e trava: exposição fixa o turno todo.
        exposureLocker?.invoke(false)
        viewModelScope.launch {
            delay(config.aeSettleMs)
            exposureLocker?.invoke(true)
        }

        _appState.value = AppState.CalibratingBackground(0f)
    }

    /** Botão "TREINAR PADRÃO" (produção normal rodando). */
    fun startProductTraining() {
        val bg = backgroundModel
        if (bg == null) {
            _uiMessage.value = "Treine o FUNDO antes do padrão."
            return
        }
        stopAlarmAndReset()
        productModel = null
        publishTrainingStatus()

        productTrainer = ProductTrainer(config, bg, effectiveEnter(bg))
        trainingStartMs = SystemClock.elapsedRealtime()
        viewModelScope.launch { metricsRepository.resetShift() }
        _appState.value = AppState.CalibratingProduct(0f, 0)
    }

    /** Botão "MONITORAR". Sem padrão treinado, opera só a contagem. */
    fun startMonitoring() {
        val bg = backgroundModel
        if (bg == null) {
            _uiMessage.value = "Treine o FUNDO antes de monitorar."
            return
        }
        stopAlarmAndReset()
        reconfigureMachine()
        if (productModel == null) {
            _uiMessage.value = "Monitorando SEM padrão: contagem ativa, defeitos desativados."
        }
        _appState.value = AppState.Monitoring
    }

    /** Botão "PARAR" durante o monitoramento. */
    fun stopMonitoring() {
        stopAlarmAndReset()
        _appState.value = AppState.Idle
    }

    /**
     * Botão "TROCAR PRODUTO": apaga fundo, padrão e métricas numa ação
     * única e guia o operador de volta ao passo 1 (feedback de campo:
     * não havia método claro para reiniciar o treinamento do zero).
     */
    fun startNewProduct() {
        stopAlarmAndReset()
        baselineStore.clearAll()
        backgroundModel = null
        productModel = null
        publishTrainingStatus()
        viewModelScope.launch { metricsRepository.resetShift() }
        _uiMessage.value = "Produto trocado. Passo 1: treine o FUNDO com a ROI vazia."
        _appState.value = AppState.Idle
    }

    // ----------------------------- ROI em campo -----------------------------

    fun enterRoiSetup() {
        stopAlarmAndReset()
        _appState.value = AppState.RoiSetup
    }

    fun cancelRoiSetup() {
        _appState.value = AppState.Idle
    }

    /** Salvar ROI invalida fundo e padrão (grades/embeddings presos ao recorte). */
    fun saveRoi(roi: RoiFractions) {
        viewModelScope.launch { settingsRepository.setRoi(roi) }
        baselineStore.clearAll()
        backgroundModel = null
        productModel = null
        itemMachine.reset()
        publishTrainingStatus()
        _uiMessage.value = "ROI salva. Retreine o fundo e o padrão para o novo formato."
        _appState.value = AppState.Idle
    }

    // --------------------------- Ajustes QA Admin ---------------------------

    fun setPresenceSensitivity(value: Float) {
        viewModelScope.launch { settingsRepository.setPresenceSensitivity(value) }
    }

    fun setShowDiagnostics(value: Boolean) {
        viewModelScope.launch { settingsRepository.setShowDiagnostics(value) }
    }

    fun resetShiftMetrics() {
        viewModelScope.launch { metricsRepository.resetShift() }
    }

    fun dismissMessage() {
        _uiMessage.value = null
    }

    private fun stopAlarmAndReset() {
        alarmStopJob?.cancel()
        alarmStopJob = null
        alarmController.stop()
        itemMachine.reset()
    }

    // --------------------- Pipeline por frame (bg thread) ---------------------

    /** Chamada pelo FrameAnalyzer no executor de fundo do CameraX. */
    fun onFrame(proxy: ImageProxy) {
        val state = _appState.value
        trackFps()

        // ROI dinâmica (ajustável em campo): recalcula o Rect só quando muda.
        val roiFractions = settings.value.roi
        val roi = if (roiFractions == cachedRoi) {
            cachedRect!!
        } else {
            RoiMapper.bufferRect(roiFractions, proxy).also {
                cachedRoi = roiFractions
                cachedRect = it
            }
        }

        // ESTÁGIO 1: grade de luma em todo frame (também alimenta o diagnóstico).
        LumaGrid.sample(proxy, roi, lumaGrid)
        val presence = backgroundModel?.presenceScore(lumaGrid) ?: 0f

        when (state) {
            is AppState.CalibratingBackground -> onBackgroundFrame()
            is AppState.CalibratingProduct -> onProductFrame(proxy, roi)
            is AppState.Monitoring, is AppState.Alarm -> {
                itemMachine.onFrame(
                    presenceScore = presence,
                    anomalyScore = anomaly@{
                        val pm = productModel ?: return@anomaly 0f
                        val score = pm.anomalyScore(
                            featureExtractor.extract(roiCropper.crop(proxy, roi))
                        )
                        lastAnomaly = score
                        score
                    },
                )
            }
            is AppState.Idle, is AppState.RoiSetup -> Unit // estágio 1 já alimenta o overlay
        }

        publishDiagnostics(presence)
    }

    private fun onBackgroundFrame() {
        val trainer = backgroundTrainer ?: return
        val elapsed = SystemClock.elapsedRealtime() - trainingStartMs
        val total = config.aeSettleMs + config.backgroundTrainingMs

        if (elapsed < total) {
            // Descarta o início: AE/AWB ainda assentando antes do lock.
            if (elapsed >= config.aeSettleMs) trainer.add(lumaGrid)
            _appState.value = AppState.CalibratingBackground(elapsed.toFloat() / total)
            return
        }

        backgroundTrainer = null
        val model = trainer.build()
        if (model == null) {
            _uiMessage.value = "Treino do FUNDO falhou: frames insuficientes. Tente novamente."
        } else {
            backgroundModel = model
            baselineStore.saveBackground(model)
            reconfigureMachine()
            _uiMessage.value = "Fundo treinado. Agora treine o PADRÃO com a produção normal."
        }
        publishTrainingStatus()
        _appState.value = AppState.Idle
    }

    private fun onProductFrame(proxy: ImageProxy, roi: Rect) {
        val trainer = productTrainer ?: return
        val elapsed = SystemClock.elapsedRealtime() - trainingStartMs

        if (elapsed < config.productTrainingMs) {
            val sampled = trainer.addFrame(lumaGrid) {
                featureExtractor.extract(roiCropper.crop(proxy, roi))
            }
            if (sampled) lastAnomaly = 0f
            _appState.value = AppState.CalibratingProduct(
                progress = elapsed.toFloat() / config.productTrainingMs,
                samples = trainer.samples,
            )
            return
        }

        productTrainer = null
        val model = trainer.build()
        if (model == null) {
            // ANTES era silencioso — causa raiz do "não contou nada" em campo.
            _uiMessage.value = "Treino do PADRÃO falhou: nenhuma garrafa detectada na ROI " +
                "(${trainer.samples} amostras). Verifique a ROI e aumente a sensibilidade."
            publishTrainingStatus()
            _appState.value = AppState.Idle
            return
        }
        productModel = model
        baselineStore.saveProduct(model)
        reconfigureMachine()
        publishTrainingStatus()
        _uiMessage.value = "Padrão treinado com ${trainer.samples} amostras. Monitorando."
        _appState.value = AppState.Monitoring
    }

    // ----------------------------- Telemetria -----------------------------

    private fun trackFps() {
        val now = SystemClock.elapsedRealtime()
        if (fpsWindowStartMs == 0L) fpsWindowStartMs = now
        fpsCounter++
        if (now - fpsWindowStartMs >= 1000L) {
            measuredFps = fpsCounter
            fpsCounter = 0
            fpsWindowStartMs = now
        }
    }

    private fun publishDiagnostics(presence: Float) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDiagPublishMs < config.debugPublishIntervalMs) return
        lastDiagPublishMs = now

        val bg = backgroundModel
        _diagnostics.value = PipelineDiagnostics(
            presenceScore = presence,
            lastAnomalyScore = lastAnomaly,
            itemState = itemMachine.state.name,
            presenceEnter = bg?.let { effectiveEnter(it) } ?: 0f,
            presenceExit = bg?.let { effectiveExit(it) } ?: 0f,
            defectThreshold = productModel?.defectThreshold ?: 0f,
            fps = measuredFps,
        )
    }

    override fun onCleared() {
        alarmController.release()
        featureExtractor.close()
        // Última gravação dos contadores (escrita pequena; scope já cancelado).
        runBlocking { metricsRepository.flush() }
    }
}
