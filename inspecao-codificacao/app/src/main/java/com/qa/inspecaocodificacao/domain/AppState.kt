package com.qa.inspecaocodificacao.domain

/**
 * Máquina de estados PRINCIPAL do aplicativo.
 *
 * Treinamento em DUAS etapas independentes (feedback de campo: a calibração
 * monolítica de 60 s era inoperável com a linha rodando):
 *
 *   IDLE ──"TREINAR FUNDO"──> CALIBRANDO_FUNDO ──> IDLE (fundo ✓)
 *   IDLE ──"TREINAR PADRÃO"──> CALIBRANDO_PADRÃO ──> MONITORANDO
 *   IDLE ──"MONITORAR"──> MONITORANDO <-> ALARME
 *   QA Admin ──"AJUSTAR ROI"──> ROI_SETUP ──> IDLE (exige retreino)
 */
sealed interface AppState {

    /** Aguardando ação do operador. O que já foi treinado vem em TrainingStatus. */
    data object Idle : AppState

    /** Aprendendo o fundo (ROI vazia). [progress] em 0..1. */
    data class CalibratingBackground(val progress: Float) : AppState

    /**
     * Aprendendo o padrão do produto bom com a produção rodando.
     * [samples] = frames com garrafa já coletados (feedback ao operador).
     */
    data class CalibratingProduct(val progress: Float, val samples: Int) : AppState

    /** Produção: estágio 1 em todo frame, estágio 2 sob demanda. */
    data object Monitoring : AppState

    /** Garrafa defeituosa na ROI: tela vermelha + apito. */
    data class Alarm(val anomalyScore: Float) : AppState

    /** Ajuste da janela de medição pelo usuário (arrastar/redimensionar). */
    data object RoiSetup : AppState
}

/** O que já foi treinado — dirige quais botões a UI habilita. */
data class TrainingStatus(
    val hasBackground: Boolean = false,
    val hasProduct: Boolean = false,
)
