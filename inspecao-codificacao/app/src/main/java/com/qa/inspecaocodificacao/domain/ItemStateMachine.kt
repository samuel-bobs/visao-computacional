package com.qa.inspecaocodificacao.domain

/**
 * Máquina de estados POR ITEM — "Histerese por Item".
 *
 * Resolve a supercontagem da câmera fixa: a contagem só acontece na
 * transição SAINDO -> VAZIO, então uma garrafa = um incremento,
 * independente de quantos frames ela ocupou.
 *
 *   VAZIO ──(presença > enter por N frames)──> ENTRANDO ──> AVALIANDO
 *     ^                                                         │
 *     └──(presença < exit por M frames: COMMIT do item)── SAINDO┘
 *
 * OTIMIZAÇÃO 18k gph: o anomaly score (TFLite, ~10-15 ms no M31) é caro
 * demais para rodar em todo frame a 30 fps. Ele entra como um SUPPLIER,
 * invocado somente nos estados em que há garrafa sob avaliação — nos
 * frames de esteira vazia (≈50% deles) o custo é só o da presença (<0,5 ms).
 */
class ItemStateMachine(
    private val config: InspectionConfig,
    private val listener: Listener,
) {

    interface Listener {
        /** Garrafa confirmada na ROI e com score acima do threshold: dispara alarme. */
        fun onDefectLive(anomalyScore: Float)

        /**
         * Garrafa saiu da ROI (Estado D confirmado). Momento único de contagem.
         * Chamada na thread de análise — o handler deve ser não-bloqueante.
         */
        fun onItemCompleted(peakScore: Float, isDefect: Boolean)
    }

    enum class State { VAZIO, ENTRANDO, AVALIANDO, SAINDO }

    var state: State = State.VAZIO
        private set

    // Thresholds auto-calibrados, injetados após a calibração (ver configure()).
    private var presenceEnterThreshold = Float.MAX_VALUE
    private var presenceExitThreshold = 0f
    private var defectThreshold = Float.MAX_VALUE

    private var debounceCount = 0
    private var peakScore = 0f
    private var defectAlreadySignaled = false

    fun configure(presenceEnter: Float, presenceExit: Float, defect: Float) {
        presenceEnterThreshold = presenceEnter
        presenceExitThreshold = presenceExit
        defectThreshold = defect
    }

    fun reset() {
        state = State.VAZIO
        debounceCount = 0
        peakScore = 0f
        defectAlreadySignaled = false
    }

    /**
     * Chamada a cada frame, sempre na MESMA thread (executor único do
     * ImageAnalysis).
     *
     * @param presenceScore  diff de luma da ROI vs fundo (estágio 1, barato)
     * @param anomalyScore   supplier do score TFLite (estágio 2, caro) —
     *                       invocado no máximo UMA vez por frame e só
     *                       quando há garrafa sob avaliação
     */
    fun onFrame(presenceScore: Float, anomalyScore: () -> Float) {
        when (state) {
            State.VAZIO -> {
                if (presenceScore > presenceEnterThreshold) {
                    if (config.enterDebounceFrames <= 1) {
                        startEvaluation(anomalyScore)
                    } else {
                        state = State.ENTRANDO
                        debounceCount = 1
                    }
                }
            }

            State.ENTRANDO -> {
                if (presenceScore > presenceEnterThreshold) {
                    if (++debounceCount >= config.enterDebounceFrames) {
                        startEvaluation(anomalyScore)
                    }
                } else {
                    // Ruído (reflexo, sombra): volta sem contar nada.
                    state = State.VAZIO
                    debounceCount = 0
                }
            }

            State.AVALIANDO -> {
                if (presenceScore < presenceExitThreshold) {
                    // Garrafa saindo: não gasta inferência neste frame.
                    state = State.SAINDO
                    debounceCount = 1
                } else {
                    evaluate(anomalyScore())
                }
            }

            State.SAINDO -> {
                if (presenceScore < presenceExitThreshold) {
                    if (++debounceCount >= config.exitDebounceFrames) {
                        commitItem()
                    }
                } else if (presenceScore > presenceEnterThreshold) {
                    // A mesma garrafa ainda está na ROI (oscilação): segue avaliando.
                    state = State.AVALIANDO
                    evaluate(anomalyScore())
                }
            }
        }
    }

    private fun startEvaluation(anomalyScore: () -> Float) {
        state = State.AVALIANDO
        debounceCount = 0
        peakScore = 0f
        defectAlreadySignaled = false
        evaluate(anomalyScore())
    }

    private fun evaluate(anomalyScore: Float) {
        if (anomalyScore > peakScore) peakScore = anomalyScore
        if (!defectAlreadySignaled && anomalyScore > defectThreshold) {
            defectAlreadySignaled = true
            listener.onDefectLive(anomalyScore)
        }
    }

    private fun commitItem() {
        val isDefect = peakScore > defectThreshold
        listener.onItemCompleted(peakScore, isDefect)
        reset()
    }
}
