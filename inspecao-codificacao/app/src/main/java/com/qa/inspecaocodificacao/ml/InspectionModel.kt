package com.qa.inspecaocodificacao.ml

import com.qa.inspecaocodificacao.domain.CellMask

/**
 * ESTÁGIO 1 — PRESENÇA v2: fração de células alteradas.
 *
 * Para cada célula ativa (não mascarada):
 *     desvio_i = luma_i − fundo_i
 *     mudou_i  = |desvio_i − shift| > max(k·σ_i, minDelta)
 * onde shift é a MEDIANA dos desvios (compensa deriva global de
 * iluminação: porta aberta, sol, lâmpada — que antes gerava falsa
 * presença em todas as células ao mesmo tempo).
 *
 *     presença = nº de células que mudaram / nº de células ativas
 *
 * Métrica robusta à fração da ROI ocupada pela garrafa: cobre 20% das
 * células ⇒ presença ≈ 0,20, independente da intensidade da mudança —
 * a média global da v1 diluía o sinal e zerava a contagem em campo.
 */
class BackgroundModel(
    val refGrid: FloatArray,
    val sigmaGrid: FloatArray,
    val presenceEnterThreshold: Float,
    val presenceExitThreshold: Float,
    cellSigmaK: Float,
    minCellDelta: Float,
) {
    /** Threshold de mudança por célula, pré-computado. */
    private val cellThreshold = FloatArray(refGrid.size) { i ->
        (cellSigmaK * sigmaGrid[i]).coerceAtLeast(minCellDelta)
    }

    // Buffers reutilizados (thread única de análise).
    private val deviations = FloatArray(refGrid.size)

    fun presenceScore(grid: FloatArray, mask: CellMask): Float {
        var n = 0
        for (i in grid.indices) {
            if (mask.isMasked(i)) continue
            deviations[n++] = grid[i] - refGrid[i]
        }
        if (n == 0) return 0f

        // Mediana dos desvios = deslocamento global de iluminação.
        // (cópia parcial + sort de até 256 floats: desprezível por frame)
        val sorted = deviations.copyOf(n)
        sorted.sort()
        val shift = sorted[n / 2]

        var changed = 0
        for (i in grid.indices) {
            if (mask.isMasked(i)) continue
            val d = grid[i] - refGrid[i] - shift
            if (d > cellThreshold[i] || -d > cellThreshold[i]) changed++
        }
        return changed.toFloat() / n
    }
}

/**
 * ESTÁGIO 2 — ANOMALIA: distância de cosseno do embedding ao centroide
 * do produto bom (‖f‖ = 1):
 *
 *     anomalia(f) = 1 − (f · μ_produto) / ‖μ_produto‖
 *
 * [presenceGate]: presença mínima para avaliar — só com a garrafa
 * CENTRALIZADA na ROI (aprendido no treino como percentil dos picos).
 * Sem o gate, frames de entrada/saída inflavam o desvio do treino e o
 * threshold de defeito ficava inalcançável.
 */
class ProductModel(
    val centroid: FloatArray,
    val defectThreshold: Float,
    val presenceGate: Float,
) {
    private val centroidNorm = VectorMath.norm(centroid)

    fun anomalyScore(embedding: FloatArray): Float =
        1f - VectorMath.dot(embedding, centroid) / centroidNorm
}

object VectorMath {
    fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    fun norm(v: FloatArray): Float {
        var s = 0f
        for (x in v) s += x * x
        return kotlin.math.sqrt(s).coerceAtLeast(1e-9f)
    }
}
