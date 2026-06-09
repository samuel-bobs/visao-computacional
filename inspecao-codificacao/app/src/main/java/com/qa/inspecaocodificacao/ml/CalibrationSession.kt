package com.qa.inspecaocodificacao.ml

import com.qa.inspecaocodificacao.camera.LumaGrid
import com.qa.inspecaocodificacao.domain.CalibrationPhase
import com.qa.inspecaocodificacao.domain.InspectionConfig
import kotlin.math.sqrt

/**
 * Acumula dados durante a calibração e constrói o InspectionModel.
 *
 * Fase 1 (EMPTY_BELT, ~10 s, descartando o assentamento do AE): esteira
 * vazia -> grade de luma de referência do fundo + thresholds de presença
 * auto-calibrados pelo ruído:
 *
 *     T_enter = ruído_médio + enterSigma * desvio   (piso enterFloor)
 *     T_exit  = ruído_médio + exitSigma  * desvio   (piso exitFloor)
 *
 * Fase 2 (PRODUCT, ~50 s): produção normal a 5 garrafas/s (~250 garrafas —
 * baseline estatisticamente sólida em uma única calibração). Só entram na
 * baseline frames com presença confirmada pelo estágio 1.
 *
 * Threshold de defeito: T = média + k*desvio dos scores do próprio
 * conjunto de calibração (k-sigma), com piso de segurança.
 */
class CalibrationSession(private val config: InspectionConfig) {

    private val emptyGrids = ArrayList<FloatArray>(512)
    private val productEmbeddings = ArrayList<FloatArray>(2048)

    private var backgroundGrid: FloatArray? = null
    var presenceEnterThreshold = 0f
        private set
    var presenceExitThreshold = 0f
        private set

    fun phaseFor(elapsedMs: Long): CalibrationPhase =
        if (elapsedMs < config.emptyCaptureDurationMs) CalibrationPhase.EMPTY_BELT
        else CalibrationPhase.PRODUCT

    /** Fase 1. O chamador deve descartar os primeiros aeSettleMs (AE/AWB assentando). */
    fun addEmptyGrid(grid: FloatArray) {
        emptyGrids.add(grid.copyOf())
    }

    /**
     * Consolida a referência de fundo na transição para a fase 2.
     * @return false se não houve frames vazios suficientes.
     */
    fun ensureBackgroundReady(): Boolean {
        if (backgroundGrid != null) return true
        if (emptyGrids.size < 30) return false

        val ref = FloatArray(LumaGrid.CELLS)
        for (g in emptyGrids) for (i in ref.indices) ref[i] += g[i]
        for (i in ref.indices) ref[i] /= emptyGrids.size

        // Ruído do fundo: distribuição dos scores dos próprios frames vazios.
        var mean = 0f
        val noise = FloatArray(emptyGrids.size) { i ->
            val s = LumaGrid.diffScore(emptyGrids[i], ref)
            mean += s
            s
        }
        mean /= noise.size
        var variance = 0f
        for (s in noise) variance += (s - mean) * (s - mean)
        val std = sqrt(variance / noise.size)

        backgroundGrid = ref
        presenceEnterThreshold = (mean + config.presenceEnterSigma * std)
            .coerceAtLeast(config.presenceEnterFloor)
        presenceExitThreshold = (mean + config.presenceExitSigma * std)
            .coerceAtLeast(config.presenceExitFloor)
        return true
    }

    /** Presença na fase 2, usada para filtrar frames sem garrafa da baseline. */
    fun presenceScore(grid: FloatArray): Float =
        LumaGrid.diffScore(grid, backgroundGrid ?: return 0f)

    fun addProductEmbedding(embedding: FloatArray) {
        productEmbeddings.add(embedding)
    }

    /** @return null se a calibração não viu garrafas suficientes (linha parada). */
    fun build(): InspectionModel? {
        val background = backgroundGrid ?: return null
        if (productEmbeddings.size < 30) return null

        val dim = productEmbeddings.first().size
        val centroid = FloatArray(dim)
        for (e in productEmbeddings) for (i in 0 until dim) centroid[i] += e[i]
        for (i in 0 until dim) centroid[i] /= productEmbeddings.size
        val centroidNorm = VectorMath.norm(centroid)

        var mean = 0f
        val scores = FloatArray(productEmbeddings.size) { i ->
            val s = 1f - VectorMath.dot(productEmbeddings[i], centroid) / centroidNorm
            mean += s
            s
        }
        mean /= scores.size
        var variance = 0f
        for (s in scores) variance += (s - mean) * (s - mean)
        val std = sqrt(variance / scores.size)

        return InspectionModel(
            backgroundGrid = background,
            presenceEnterThreshold = presenceEnterThreshold,
            presenceExitThreshold = presenceExitThreshold,
            productCentroid = centroid,
            defectThreshold = (mean + config.defectThresholdK * std)
                .coerceAtLeast(config.minDefectThreshold),
        )
    }
}
