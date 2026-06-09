package com.qa.inspecaocodificacao.domain

/**
 * Máquina de estados PRINCIPAL do aplicativo.
 *
 * IDLE -> CALIBRANDO -> MONITORANDO <-> ALARME
 *            |                ^
 *            +----------------+
 *
 * O estado ALARME é transitório: dispara enquanto uma garrafa defeituosa
 * está na ROI e se auto-reseta quando ela sai (Estado D do item).
 */
sealed interface AppState {

    /** App aberto, aguardando o operador iniciar a calibração. */
    data object Idle : AppState

    /** Aprendendo a baseline do produto atual. [progress] em 0..1. */
    data class Calibrating(val progress: Float, val phase: CalibrationPhase) : AppState

    /** Produção: comparando cada frame contra a baseline. */
    data object Monitoring : AppState

    /** Garrafa defeituosa na ROI: tela vermelha + apito + flash. */
    data class Alarm(val anomalyScore: Float) : AppState
}

enum class CalibrationPhase {
    /** Esteira vazia: aprendendo o fundo (referência de presença). */
    EMPTY_BELT,

    /** Produção normal passando: aprendendo a assinatura do produto bom. */
    PRODUCT,
}
