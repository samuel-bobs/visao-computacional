package com.qa.inspecaocodificacao.domain

/**
 * Parâmetros de operação da inspeção.
 *
 * Todos os valores foram pensados para uma câmera FIXA olhando para um ponto
 * único da esteira. Ajuste os thresholds durante o comissionamento na linha.
 */
data class InspectionConfig(
    /** Duração total da calibração (aprendizado da baseline do produto). */
    val calibrationDurationMs: Long = 60_000L,

    /**
     * Janela inicial da calibração em que a esteira deve estar VAZIA.
     * Usada para aprender o centroide do "fundo" (referência de presença).
     */
    val emptyCaptureDurationMs: Long = 10_000L,

    /**
     * Região de Interesse, em frações do frame (esq, topo, dir, base).
     * Cobre apenas a faixa da garrafa onde a codificação é aplicada —
     * quanto menor a ROI, menor a latência e maior a sensibilidade.
     */
    val roiLeft: Float = 0.35f,
    val roiTop: Float = 0.25f,
    val roiRight: Float = 0.65f,
    val roiBottom: Float = 0.75f,

    /**
     * Histerese de presença: o limiar de ENTRADA é maior que o de SAÍDA
     * para que ruído de iluminação não gere oscilação entre estados.
     */
    val presenceEnterThreshold: Float = 0.18f,
    val presenceExitThreshold: Float = 0.10f,

    /** Frames consecutivos exigidos para confirmar entrada/saída (debounce). */
    val enterDebounceFrames: Int = 2,
    val exitDebounceFrames: Int = 3,

    /**
     * Threshold de defeito = média + k * desvio-padrão dos scores de
     * calibração (auto-calibrado por produto). minDefectThreshold é o piso
     * de segurança caso a calibração tenha variância quase nula.
     */
    val defectThresholdK: Float = 4.0f,
    val minDefectThreshold: Float = 0.08f,
)
