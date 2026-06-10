package com.qa.inspecaocodificacao.ml

import com.qa.inspecaocodificacao.camera.LumaGrid
import com.qa.inspecaocodificacao.domain.InspectionConfig
import kotlin.math.sqrt

/**
 * Treinadores independentes — um por botão da UI.
 *
 * BackgroundTrainer ("TREINAR FUNDO"): com a ROI vazia, acumula grades de
 * luma e produz a referência do fundo + thresholds de presença
 * auto-calibrados pelo ruído (k-sigma com pisos).
 *
 * ProductTrainer ("TREINAR PADRÃO"): com a produção normal rodando e o
 * fundo já treinado, acumula embeddings apenas dos frames COM garrafa
 * (gate de presença) e produz o centroide + threshold de defeito.
 */
class BackgroundTrainer(private val config: InspectionConfig) {

    private val grids = ArrayList<FloatArray>(1024)

    val samples: Int get() = grids.size

    fun add(grid: FloatArray) {
        grids.add(grid.copyOf())
    }

    /** @return null se não houve frames suficientes. */
    fun build(): BackgroundModel? {
        if (grids.size < 30) return null

        val ref = FloatArray(LumaGrid.CELLS)
        for (g in grids) for (i in ref.indices) ref[i] += g[i]
        for (i in ref.indices) ref[i] /= grids.size

        // Ruído do fundo: distribuição dos scores dos próprios frames vazios.
        var mean = 0f
        val noise = FloatArray(grids.size) { i ->
            val s = LumaGrid.diffScore(grids[i], ref)
            mean += s
            s
        }
        mean /= noise.size
        var variance = 0f
        for (s in noise) variance += (s - mean) * (s - mean)
        val std = sqrt(variance / noise.size)

        return BackgroundModel(
            grid = ref,
            presenceEnterThreshold = (mean + config.presenceEnterSigma * std)
                .coerceAtLeast(config.presenceEnterFloor),
            presenceExitThreshold = (mean + config.presenceExitSigma * std)
                .coerceAtLeast(config.presenceExitFloor),
        )
    }
}

class ProductTrainer(
    private val config: InspectionConfig,
    private val background: BackgroundModel,
    /** Threshold efetivo (já com a sensibilidade do usuário aplicada). */
    private val presenceGate: Float,
) {

    private val embeddings = ArrayList<FloatArray>(2048)

    val samples: Int get() = embeddings.size

    /**
     * @param embeddingSupplier invocado (TFLite) somente se a presença
     *        passar no gate — mesmo princípio do pipeline de produção.
     * @return true se o frame foi amostrado.
     */
    fun addFrame(lumaGrid: FloatArray, embeddingSupplier: () -> FloatArray): Boolean {
        if (background.presenceScore(lumaGrid) <= presenceGate) return false
        embeddings.add(embeddingSupplier())
        return true
    }

    /** @return null se a calibração não viu garrafas suficientes (linha parada). */
    fun build(): ProductModel? {
        if (embeddings.size < 30) return null

        val dim = embeddings.first().size
        val centroid = FloatArray(dim)
        for (e in embeddings) for (i in 0 until dim) centroid[i] += e[i]
        for (i in 0 until dim) centroid[i] /= embeddings.size
        val centroidNorm = VectorMath.norm(centroid)

        var mean = 0f
        val scores = FloatArray(embeddings.size) { i ->
            val s = 1f - VectorMath.dot(embeddings[i], centroid) / centroidNorm
            mean += s
            s
        }
        mean /= scores.size
        var variance = 0f
        for (s in scores) variance += (s - mean) * (s - mean)
        val std = sqrt(variance / scores.size)

        return ProductModel(
            centroid = centroid,
            defectThreshold = (mean + config.defectThresholdK * std)
                .coerceAtLeast(config.minDefectThreshold),
        )
    }
}
