package com.qa.inspecaocodificacao.ml

import com.qa.inspecaocodificacao.domain.CalibrationPhase
import com.qa.inspecaocodificacao.domain.InspectionConfig
import kotlin.math.sqrt

/**
 * Acumula embeddings durante a calibração e constrói o AnomalyModel.
 *
 * Fase 1 (EMPTY_BELT, ~10 s): esteira vazia -> centroide μ_vazio.
 * Fase 2 (PRODUCT, ~50 s): produção normal rodando. Só entram na baseline
 * do produto os frames em que HÁ garrafa na ROI (presença > enterThreshold),
 * para que o fundo não contamine a assinatura do produto.
 *
 * Threshold automático: ao final, cada embedding de produto é pontuado
 * contra o próprio centroide; o threshold de defeito é
 *
 *     T = média(scores) + k * desvio(scores)      (piso: minDefectThreshold)
 *
 * ou seja, "k sigmas" acima da variabilidade natural do produto bom.
 */
class CalibrationSession(private val config: InspectionConfig) {

    private val emptyEmbeddings = ArrayList<FloatArray>(128)
    private val productEmbeddings = ArrayList<FloatArray>(1024)

    private var emptyCentroid: FloatArray? = null

    fun phaseFor(elapsedMs: Long): CalibrationPhase =
        if (elapsedMs < config.emptyCaptureDurationMs) CalibrationPhase.EMPTY_BELT
        else CalibrationPhase.PRODUCT

    fun addFrame(embedding: FloatArray, elapsedMs: Long) {
        when (phaseFor(elapsedMs)) {
            CalibrationPhase.EMPTY_BELT -> emptyEmbeddings.add(embedding)

            CalibrationPhase.PRODUCT -> {
                val empty = emptyCentroid ?: centroid(emptyEmbeddings).also { emptyCentroid = it }
                val presence = 1f - AnomalyModel.dot(embedding, empty) / AnomalyModel.norm(empty)
                if (presence > config.presenceEnterThreshold) {
                    productEmbeddings.add(embedding)
                }
            }
        }
    }

    /** @return null se a calibração não viu garrafas suficientes (linha parada). */
    fun build(): AnomalyModel? {
        if (emptyEmbeddings.size < 10 || productEmbeddings.size < 30) return null

        val empty = emptyCentroid ?: centroid(emptyEmbeddings)
        val product = centroid(productEmbeddings)
        val productNorm = AnomalyModel.norm(product)

        // Distribuição dos scores do próprio conjunto de calibração.
        var mean = 0f
        val scores = FloatArray(productEmbeddings.size) { i ->
            val s = 1f - AnomalyModel.dot(productEmbeddings[i], product) / productNorm
            mean += s
            s
        }
        mean /= scores.size

        var variance = 0f
        for (s in scores) variance += (s - mean) * (s - mean)
        val std = sqrt(variance / scores.size)

        val threshold = (mean + config.defectThresholdK * std)
            .coerceAtLeast(config.minDefectThreshold)

        return AnomalyModel(empty, product, threshold)
    }

    private fun centroid(embeddings: List<FloatArray>): FloatArray {
        val dim = embeddings.first().size
        val c = FloatArray(dim)
        for (e in embeddings) {
            for (i in 0 until dim) c[i] += e[i]
        }
        for (i in 0 until dim) c[i] /= embeddings.size
        return c
    }
}
