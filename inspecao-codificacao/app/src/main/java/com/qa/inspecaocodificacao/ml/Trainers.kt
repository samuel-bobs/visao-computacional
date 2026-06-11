package com.qa.inspecaocodificacao.ml

import com.qa.inspecaocodificacao.camera.LumaGrid
import com.qa.inspecaocodificacao.domain.CellMask
import com.qa.inspecaocodificacao.domain.InspectionConfig
import kotlin.math.sqrt

/**
 * BackgroundTrainer ("TREINAR FUNDO"): com a ROI vazia, aprende média E
 * desvio POR CÉLULA (ruído local: esteira vibrando, respingos) e os
 * thresholds de presença sobre a fração de células alteradas.
 */
class BackgroundTrainer(private val config: InspectionConfig) {

    private val grids = ArrayList<FloatArray>(1024)

    val samples: Int get() = grids.size

    fun add(grid: FloatArray) {
        grids.add(grid.copyOf())
    }

    fun build(mask: CellMask): BackgroundModel? {
        if (grids.size < 30) return null

        val n = grids.size
        val ref = FloatArray(LumaGrid.CELLS)
        for (g in grids) for (i in ref.indices) ref[i] += g[i]
        for (i in ref.indices) ref[i] /= n

        val sigma = FloatArray(LumaGrid.CELLS)
        for (g in grids) for (i in sigma.indices) {
            val d = g[i] - ref[i]
            sigma[i] += d * d
        }
        for (i in sigma.indices) sigma[i] = sqrt(sigma[i] / n)

        // Ruído residual da presença sobre os próprios frames vazios.
        val probe = BackgroundModel(ref, sigma, 0f, 0f, config.cellSigmaK, config.minCellDelta)
        var mean = 0f
        val noise = FloatArray(n) { i ->
            val s = probe.presenceScore(grids[i], mask)
            mean += s
            s
        }
        mean /= n
        var variance = 0f
        for (s in noise) variance += (s - mean) * (s - mean)
        val std = sqrt(variance / n)

        return BackgroundModel(
            refGrid = ref,
            sigmaGrid = sigma,
            presenceEnterThreshold = (mean + config.presenceEnterSigma * std)
                .coerceAtLeast(config.presenceEnterFloor),
            presenceExitThreshold = (mean + config.presenceExitSigma * std)
                .coerceAtLeast(config.presenceExitFloor),
            cellSigmaK = config.cellSigmaK,
            minCellDelta = config.minCellDelta,
        )
    }
}

/**
 * ProductTrainer ("TREINAR PADRÃO"): coleta pares (presença, embedding)
 * dos frames com garrafa e, no build, aplica o GATE DE CENTRALIZAÇÃO:
 * só os frames acima do percentil de presença entram no centroide —
 * garrafa centralizada na ROI, sem os frames de entrada/saída que
 * inflavam o desvio e inviabilizavam o threshold de defeito.
 */
class ProductTrainer(
    private val config: InspectionConfig,
    private val background: BackgroundModel,
    private val mask: CellMask,
    /** Threshold efetivo de coleta (com a sensibilidade do usuário). */
    private val presenceGate: Float,
) {

    private val presences = ArrayList<Float>(2048)
    private val embeddings = ArrayList<FloatArray>(2048)

    val samples: Int get() = embeddings.size

    /** @return true se o frame foi amostrado (TFLite só roda nesse caso). */
    fun addFrame(lumaGrid: FloatArray, embeddingSupplier: () -> FloatArray): Boolean {
        val presence = background.presenceScore(lumaGrid, mask)
        if (presence <= presenceGate) return false
        presences.add(presence)
        embeddings.add(embeddingSupplier())
        return true
    }

    fun build(): ProductModel? {
        if (embeddings.size < 30) return null

        // Gate de centralização: percentil das presenças coletadas.
        val sorted = presences.toFloatArray().also { it.sort() }
        val gateIdx = (sorted.size * config.productGatePercentile).toInt()
            .coerceIn(0, sorted.size - 1)
        val centerGate = sorted[gateIdx]

        val selected = embeddings.indices.filter { presences[it] >= centerGate }
        // Fallback: se o filtro for agressivo demais, usa a metade superior.
        val chosen = if (selected.size >= 15) selected
        else embeddings.indices.sortedBy { presences[it] }.takeLast(embeddings.size / 2)
        if (chosen.size < 15) return null

        val dim = embeddings.first().size
        val centroid = FloatArray(dim)
        for (idx in chosen) {
            val e = embeddings[idx]
            for (i in 0 until dim) centroid[i] += e[i]
        }
        for (i in 0 until dim) centroid[i] /= chosen.size
        val centroidNorm = VectorMath.norm(centroid)

        var mean = 0f
        val scores = FloatArray(chosen.size)
        for ((k, idx) in chosen.withIndex()) {
            val s = 1f - VectorMath.dot(embeddings[idx], centroid) / centroidNorm
            scores[k] = s
            mean += s
        }
        mean /= scores.size
        var variance = 0f
        for (s in scores) variance += (s - mean) * (s - mean)
        val std = sqrt(variance / scores.size)

        return ProductModel(
            centroid = centroid,
            defectThreshold = (mean + config.defectThresholdK * std)
                .coerceAtLeast(config.minDefectThreshold),
            presenceGate = centerGate,
        )
    }
}
