package com.qa.inspecaocodificacao.domain

/**
 * Parâmetros de operação da inspeção.
 *
 * DIMENSIONAMENTO (piloto):
 *  - Dispositivo: Samsung Galaxy M31 (Exynos 9611: 4x A73 + 4x A53, Mali-G72 MP3)
 *  - Linha: ~18.000 garrafas/hora = 5 garrafas/s = ciclo de 200 ms
 *  - Câmera: 60 fps quando o sensor suportar (12 frames/ciclo), senão 30 fps
 *    (6 frames/ciclo). Os frames de slow-motion real (120-480 fps) do M31
 *    não chegam ao ImageAnalysis (sessão high-speed só alimenta o encoder
 *    de vídeo) — 60 fps é o teto utilizável para inferência.
 */
data class InspectionConfig(
    /** Duração do treino de FUNDO (ROI vazia), após o assentamento do AE. */
    val backgroundTrainingMs: Long = 10_000L,

    /** Duração do treino de PADRÃO (produção normal: ~200 garrafas a 18k gph). */
    val productTrainingMs: Long = 45_000L,

    /**
     * Início do treino de fundo descartado: tempo para o AE/AWB assentarem
     * antes do lock de exposição.
     */
    val aeSettleMs: Long = 2_500L,

    /**
     * PRESENÇA v2 — fração de células alteradas (background subtraction):
     * cada célula tem ruído próprio aprendido no treino de fundo; uma célula
     * "mudou" se |desvio − deslocamento global| > max(cellSigmaK*σ_célula,
     * minCellDelta). O score é a FRAÇÃO de células ativas que mudaram —
     * robusto a garrafas que ocupam só parte da ROI (a métrica antiga de
     * média diluía o sinal e zerava a contagem em campo).
     */
    val cellSigmaK: Float = 6f,
    val minCellDelta: Float = 8f, // níveis de luma (0-255)

    /**
     * Thresholds de presença sobre a fração de células: auto-calibrados pelo
     * ruído do fundo (k-sigma) com pisos interpretáveis — entrar exige ~12%
     * das células alteradas, sair é voltar abaixo de ~6%.
     */
    val presenceEnterSigma: Float = 8f,
    val presenceExitSigma: Float = 4f,
    val presenceEnterFloor: Float = 0.12f,
    val presenceExitFloor: Float = 0.06f,

    /**
     * Gate de centralização do estágio 2: o treino do padrão aprende o pico
     * típico de presença e só usa (no treino E na inferência) frames acima
     * deste percentil — garrafas meio dentro/meio fora inflavam o desvio e
     * tornavam o threshold de defeito inalcançável (defeitos nunca contavam).
     */
    val productGatePercentile: Float = 0.60f,

    /** Debounce para 5 garrafas/s (1 confirma entrada, 2 confirmam saída). */
    val enterDebounceFrames: Int = 1,
    val exitDebounceFrames: Int = 2,

    /** Threshold de defeito = média + k * desvio dos scores de calibração. */
    val defectThresholdK: Float = 4.0f,
    val minDefectThreshold: Float = 0.08f,

    /** Retenção mínima do alarme após a saída do item defeituoso. */
    val alarmMinDurationMs: Long = 2_000L,

    /** Torch desligado: piscar o flash contaminaria os scores das garrafas
     *  inspecionadas DURANTE o alarme (5/s passam com o alarme ativo). */
    val useTorchOnAlarm: Boolean = false,

    /** Mali-G72 MP3: XNNPACK na CPU vence o delegate GPU p/ modelos pequenos. */
    val useGpuDelegate: Boolean = false,

    /** Flush em lote das métricas (evita 5 fsyncs/s no DataStore). */
    val metricsFlushIntervalMs: Long = 1_500L,

    /**
     * Teto de FPS da análise. O app consulta os ranges suportados pelo
     * sensor e escolhe o maior FIXO até este valor (60 no M31 dobra a
     * resolução temporal: 12 frames/ciclo a 18k gph).
     */
    val maxAnalysisFps: Int = 60,

    /** Intervalo de publicação do overlay de diagnóstico (não a cada frame). */
    val debugPublishIntervalMs: Long = 150L,
)
