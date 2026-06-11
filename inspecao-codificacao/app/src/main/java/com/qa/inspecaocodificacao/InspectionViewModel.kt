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
import com.qa.inspecaocodificacao.domain.CellMask
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
    val presenceScore: Float = 0f,       // fração de células alteradas (0..1)
    val lastAnomalyScore: Float = 0f,
    val itemState: String = "—",
    val presenceEnter: Float = 0f,
    val presenceExit: Float = 0f,
    val defectThreshold: Float = 0f,
    val centerGate: Float = 0f,
    val fps: Int = 0,
)

/**
 * Orquestrador central — revisão profunda pós-campo:
 *  - Presença v2: fração de células alteradas com ruído por célula e
 *    compensação de iluminação (a média global diluía o sinal);
 *  - Gate de centralização no estágio 2 (treino e inferência só com a
 *    garrafa centralizada — corrige threshold de defeito inalcançável);
 *  - Máscara de células editável pelo usuário (exclui reflexos/máquina);
 *  - Zoom da câmera ajustável e persistido.
 */
class InspectionViewModel(application: Application) : AndroidViewModel(application) {

    val config = InspectionConfig()

    private val featureExtractor = FeatureExtractor(application, config.useGpuDelegate)
    private val baselineStore = BaselineStore(application, config)
    private val settingsRepository = SettingsRepository(application)
    private val metricsRepository =
        MetricsRepository(application, viewModelScope, config.metricsFlushIntervalMs)
    val alarmController = AlarmController(application, viewModelScope, config.useTorchOnAlarm)

    // ----- Integração com a câmera (injetada pela Activity após o bind) -----
    @Volatile var exposureLocker: ((Boolean) -> Unit)? = null
    @Volatile private var zoomApplier: ((Float) -> Unit)? = null

    private val _maxZoom = MutableStateFlow(1f)
    val maxZoom: StateFlow<Float> = _maxZoom.asStateFlow()

    fun attachCameraZoom(maxZoomRatio: Float, applier: (Float) -> Unit) {
        _maxZoom.value = maxZoomRatio
        zoomApplier = applier
        applier(settings.value.zoomRatio)
    }

    /** Zoom ao vivo durante o ajuste (persistido apenas no SALVAR). */
    fun previewZoom(ratio: Float) {
        zoomApplier?.invoke(ratio)
    }

    // ------------------------------- Estado --------------------------------
    private val _appState = MutableStateFlow<AppState>(AppState.Idle)
    val appState: StateFlow<AppState> = _appState.asStateFlow()

    private val _trainingStatus = MutableStateFlow(TrainingStatus())
    val trainingStatus: StateFlow<TrainingStatus> = _trainingStatus.asStateFlow()

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
    private var cachedMaskString: String? = null
    private var cachedRotation = -1
    private var bufferMask: CellMask = CellMask.empty()

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

        // Sensibilidade/zoom mudam em runtime.
        viewModelScope.launch {
            settings.collect {
                reconfigureMachine()
                zoomApplier?.invoke(it.zoomRatio)
            }
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
        productModel = null
        baselineStore.clearProduct()
        backgroundModel = null
        publishTrainingStatus()

        backgroundTrainer = BackgroundTrainer(config)
        trainingStartMs = SystemClock.elapsedRealtime()

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

        productTrainer = ProductTrainer(config, bg, bufferMask, effectiveEnter(bg))
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

    /** Botão "TROCAR PRODUTO": apaga fundo, padrão e métricas. */
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

    // -------------------- Ajuste de campo (ROI/máscara/zoom) --------------------

    fun enterRoiSetup() {
        stopAlarmAndReset()
        _appState.value = AppState.RoiSetup
    }

    fun cancelRoiSetup() {
        // Restaura o zoom persistido (o slider pode ter mudado ao vivo).
        zoomApplier?.invoke(settings.value.zoomRatio)
        _appState.value = AppState.Idle
    }

    /** ROI + máscara + zoom salvos juntos; tudo treinado deixa de valer. */
    fun saveMeasurementSetup(roi: RoiFractions, maskedCells: Set<Int>, zoomRatio: Float) {
        viewModelScope.launch {
            settingsRepository.setMeasurementSetup(
                roi = roi,
                maskString = CellMask.serialize(maskedCells),
                zoomRatio = zoomRatio,
            )
        }
        baselineStore.clearAll()
        backgroundModel = null
        productModel = null
        itemMachine.reset()
        publishTrainingStatus()
        _uiMessage.value = "Ajuste salvo. Retreine o fundo e o padrão para a nova janela."
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

        val current = settings.value
        val rotation = proxy.imageInfo.rotationDegrees

        // Caches dependentes de ROI/máscara/rotação (mudam raramente).
        val roi = if (current.roi == cachedRoi && rotation == cachedRotation) {
            cachedRect!!
        } else {
            RoiMapper.bufferRect(current.roi, proxy).also {
                cachedRoi = current.roi
                cachedRect = it
            }
        }
        if (current.maskString != cachedMaskString || rotation != cachedRotation) {
            bufferMask = CellMask.fromString(current.maskString).toBufferOrientation(rotation)
            cachedMaskString = current.maskString
        }
        cachedRotation = rotation

        // ESTÁGIO 1: grade de luma + fração de células alteradas.
        LumaGrid.sample(proxy, roi, lumaGrid)
        val presence = backgroundModel?.presenceScore(lumaGrid, bufferMask) ?: 0f

        when (state) {
            is AppState.CalibratingBackground -> onBackgroundFrame()
            is AppState.CalibratingProduct -> onProductFrame(proxy, roi)
            is AppState.Monitoring, is AppState.Alarm -> {
                itemMachine.onFrame(
                    presenceScore = presence,
                    anomalyScore = anomaly@{
                        val pm = productModel ?: return@anomaly 0f
                        // Gate de centralização: TFLite só com a garrafa
                        // centralizada na ROI (frames de borda distorcem).
                        if (presence < pm.presenceGate) return@anomaly 0f
                        val score = pm.anomalyScore(
                            featureExtractor.extract(roiCropper.crop(proxy, roi, bufferMask))
                        )
                        lastAnomaly = score
                        score
                    },
                )
            }
            is AppState.Idle, is AppState.RoiSetup -> Unit // estágio 1 alimenta o overlay
        }

        publishDiagnostics(presence)
    }

    private fun onBackgroundFrame() {
        val trainer = backgroundTrainer ?: return
        val elapsed = SystemClock.elapsedRealtime() - trainingStartMs
        val total = config.aeSettleMs + config.backgroundTrainingMs

        if (elapsed < total) {
            if (elapsed >= config.aeSettleMs) trainer.add(lumaGrid)
            _appState.value = AppState.CalibratingBackground(elapsed.toFloat() / total)
            return
        }

        backgroundTrainer = null
        val model = trainer.build(bufferMask)
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
            trainer.addFrame(lumaGrid) {
                featureExtractor.extract(roiCropper.crop(proxy, roi, bufferMask))
            }
            _appState.value = AppState.CalibratingProduct(
                progress = elapsed.toFloat() / config.productTrainingMs,
                samples = trainer.samples,
            )
            return
        }

        productTrainer = null
        val model = trainer.build()
        if (model == null) {
            _uiMessage.value = "Treino do PADRÃO falhou: poucas garrafas detectadas na ROI " +
                "(${trainer.samples} amostras). Verifique a janela e aumente a sensibilidade."
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
            centerGate = productModel?.presenceGate ?: 0f,
            fps = measuredFps,
        )
    }

    override fun onCleared() {
        alarmController.release()
        featureExtractor.close()
        runBlocking { metricsRepository.flush() }
    }
}
