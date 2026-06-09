package com.qa.inspecaocodificacao.domain

/**
 * Parâmetros de operação da inspeção.
 *
 * DIMENSIONAMENTO (piloto):
 *  - Dispositivo: Samsung Galaxy M31 (Exynos 9611: 4x A73 + 4x A53, Mali-G72 MP3)
 *  - Linha: ~18.000 garrafas/hora = 5 garrafas/s = ciclo de 200 ms
 *  - Câmera a 30 fps => ~6 frames por ciclo (~3 com garrafa na ROI, ~3 de vão)
 *
 * Com 6 frames/ciclo o orçamento é apertado: a presença é detectada por
 * diferença de luma (estágio 1, <0,5 ms) em TODO frame, e o TFLite (estágio 2)
 * roda apenas nos frames com garrafa presente.
 */
data class InspectionConfig(
    /** Duração total da calibração (aprendizado da baseline do produto). */
    val calibrationDurationMs: Long = 60_000L,

    /**
     * Janela inicial da calibração em que a esteira deve estar VAZIA.
     * Usada para aprender a grade de referência do fundo.
     */
    val emptyCaptureDurationMs: Long = 10_000L,

    /**
     * Início da fase vazia descartado: tempo para o AE/AWB assentarem antes
     * do lock de exposição (senão a referência de fundo fica contaminada).
     */
    val aeSettleMs: Long = 2_500L,

    /**
     * Região de Interesse, em frações do frame (esq, topo, dir, base).
     * Cobre apenas a faixa da garrafa onde a codificação é aplicada.
     */
    val roiLeft: Float = 0.35f,
    val roiTop: Float = 0.25f,
    val roiRight: Float = 0.65f,
    val roiBottom: Float = 0.75f,

    /**
     * Thresholds de presença AUTO-CALIBRADOS a partir do ruído do fundo:
     *   T_enter = ruído_médio + enterSigma * desvio   (piso: enterFloor)
     *   T_exit  = ruído_médio + exitSigma  * desvio   (piso: exitFloor)
     * enterSigma > exitSigma mantém a histerese.
     */
    val presenceEnterSigma: Float = 8f,
    val presenceExitSigma: Float = 4f,
    val presenceEnterFloor: Float = 0.020f,
    val presenceExitFloor: Float = 0.012f,

    /**
     * Debounce dimensionado para 5 garrafas/s a 30 fps (6 frames/ciclo):
     * 1 frame confirma entrada, 2 confirmam saída. O total por ciclo
     * (1 entrada + ~2 avaliação + 2 saída) cabe nos 6 frames disponíveis.
     */
    val enterDebounceFrames: Int = 1,
    val exitDebounceFrames: Int = 2,

    /** Threshold de defeito = média + k * desvio dos scores de calibração. */
    val defectThresholdK: Float = 4.0f,
    val minDefectThreshold: Float = 0.08f,

    /**
     * A 5 garrafas/s, a garrafa defeituosa sai da ROI em ~100-200 ms — um
     * alarme que reseta na saída seria imperceptível. O alarme é mantido por
     * no mínimo este tempo APÓS a saída do item (a contagem do defeito já
     * foi registrada na saída; defeitos consecutivos re-armam o alarme).
     */
    val alarmMinDurationMs: Long = 2_000L,

    /**
     * Flash desligado por padrão: o torch piscando muda a iluminação da cena
     * e contaminaria os scores das garrafas seguintes (5/s passam DURANTE o
     * alarme). O alerta visual fica por conta da tela vermelha pulsante.
     */
    val useTorchOnAlarm: Boolean = false,

    /**
     * Mali-G72 MP3 (M31): para o MobileNetV3-Small o delegate GPU perde para
     * o XNNPACK nos 4x A73 (overhead de upload/dispatch domina em modelos
     * pequenos). NNAPI no Exynos 9611 é instável — evitado.
     */
    val useGpuDelegate: Boolean = false,

    /**
     * A 5 itens/s, persistir no DataStore a cada garrafa seriam 5 fsyncs/s.
     * Contadores ficam em memória e são gravados neste intervalo
     * (janela máxima de perda em queda de energia: 1,5 s ≈ 7 garrafas).
     */
    val metricsFlushIntervalMs: Long = 1_500L,

    /** FPS fixo (trava o AE de variar o frame rate e o intervalo entre frames). */
    val targetFps: Int = 30,
)
