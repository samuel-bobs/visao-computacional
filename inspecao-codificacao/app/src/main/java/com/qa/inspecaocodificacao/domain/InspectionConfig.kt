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
     * Thresholds de presença AUTO-CALIBRADOS a partir do ruído do fundo:
     *   T_enter = ruído_médio + enterSigma * desvio   (piso: enterFloor)
     *   T_exit  = ruído_médio + exitSigma  * desvio   (piso: exitFloor)
     * O usuário ainda aplica um multiplicador de sensibilidade em campo
     * (modo QA Admin) sem retreinar.
     */
    val presenceEnterSigma: Float = 8f,
    val presenceExitSigma: Float = 4f,
    val presenceEnterFloor: Float = 0.020f,
    val presenceExitFloor: Float = 0.012f,

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
