package com.qa.inspecaocodificacao.domain

/**
 * Máquina de estados POR ITEM — "Histerese por Item".
 *
 * Resolve a supercontagem da câmera fixa: uma garrafa parada na ROI gera
 * centenas de frames, mas só conta como UM item, porque a contagem só
 * acontece na transição SAINDO -> VAZIO.
 *
 *   VAZIO ──(presença > enter por N frames)──> ENTRANDO ──> AVALIANDO
 *     ^                                                         │
 *     └──(presença < exit por M frames: COMMIT do item)── SAINDO┘
 *
 * Durante AVALIANDO o sistema registra o PICO do anomaly score do item.
 * No commit (Estado D confirmado):
 *   - totalInspecionado++
 *   - se pico > threshold: totalDefeitos++
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
         * Também é o gatilho do AUTO-RESET do alarme.
         */
        fun onItemCompleted(peakScore: Float, isDefect: Boolean)
    }

    enum class State { VAZIO, ENTRANDO, AVALIANDO, SAINDO }

    var state: State = State.VAZIO
        private set

    private var debounceCount = 0
    private var peakScore = 0f
    private var defectAlreadySignaled = false
    private var defectThreshold = Float.MAX_VALUE

    fun setDefectThreshold(threshold: Float) {
        defectThreshold = threshold
    }

    fun reset() {
        state = State.VAZIO
        debounceCount = 0
        peakScore = 0f
        defectAlreadySignaled = false
    }

    /**
     * Deve ser chamada a cada frame analisado, sempre na MESMA thread
     * (o executor único do ImageAnalysis garante isso).
     *
     * @param presenceScore distância do frame ao centroide de "esteira vazia"
     * @param anomalyScore  distância do frame ao centroide de "produto bom"
     */
    fun onFrame(presenceScore: Float, anomalyScore: Float) {
        when (state) {
            State.VAZIO -> {
                if (presenceScore > config.presenceEnterThreshold) {
                    state = State.ENTRANDO
                    debounceCount = 1
                }
            }

            State.ENTRANDO -> {
                if (presenceScore > config.presenceEnterThreshold) {
                    if (++debounceCount >= config.enterDebounceFrames) {
                        // Presença confirmada: começa a avaliação do item.
                        state = State.AVALIANDO
                        peakScore = 0f
                        defectAlreadySignaled = false
                        evaluate(anomalyScore)
                    }
                } else {
                    // Ruído (reflexo, sombra): volta sem contar nada.
                    state = State.VAZIO
                    debounceCount = 0
                }
            }

            State.AVALIANDO -> {
                evaluate(anomalyScore)
                if (presenceScore < config.presenceExitThreshold) {
                    state = State.SAINDO
                    debounceCount = 1
                }
            }

            State.SAINDO -> {
                if (presenceScore < config.presenceExitThreshold) {
                    if (++debounceCount >= config.exitDebounceFrames) {
                        commitItem()
                    }
                } else if (presenceScore > config.presenceEnterThreshold) {
                    // A mesma garrafa ainda está na ROI (oscilação): segue avaliando.
                    state = State.AVALIANDO
                    evaluate(anomalyScore)
                }
            }
        }
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
