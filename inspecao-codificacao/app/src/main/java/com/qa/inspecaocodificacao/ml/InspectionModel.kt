package com.qa.inspecaocodificacao.ml

import com.qa.inspecaocodificacao.camera.LumaGrid

/**
 * Modelos da baseline, agora INDEPENDENTES (feedback de campo: treinar
 * fundo e padrão em momentos separados, conforme a janela operacional).
 *
 * ESTÁGIO 1 — presença (todo frame, <0,5 ms):
 *     presença(grade) = média(|grade − gradeFundo|) / 255
 *
 * ESTÁGIO 2 — anomalia (só com garrafa presente, ~10-15 ms no M31):
 *     anomalia(f) = 1 − (f · μ_produto) / ‖μ_produto‖      (‖f‖ = 1)
 */
class BackgroundModel(
    val grid: FloatArray,
    val presenceEnterThreshold: Float,
    val presenceExitThreshold: Float,
) {
    fun presenceScore(lumaGrid: FloatArray): Float =
        LumaGrid.diffScore(lumaGrid, grid)
}

class ProductModel(
    val centroid: FloatArray,
    val defectThreshold: Float,
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
