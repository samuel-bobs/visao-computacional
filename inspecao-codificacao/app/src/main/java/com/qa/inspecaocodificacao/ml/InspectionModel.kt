package com.qa.inspecaocodificacao.ml

import com.qa.inspecaocodificacao.camera.LumaGrid

/**
 * Baseline aprendida na calibração — pipeline de DOIS estágios:
 *
 * ESTÁGIO 1 — presença (todo frame, <0,5 ms):
 *     presença(grade) = média(|grade − gradeFundo|) / 255
 *   Diferença de luma da ROI contra a referência de esteira vazia. Sobe
 *   bruscamente quando a garrafa entra (Estado B). Os thresholds de
 *   entrada/saída são auto-calibrados a partir do ruído do próprio fundo.
 *
 * ESTÁGIO 2 — anomalia (só com garrafa presente, ~10-15 ms no M31):
 *     anomalia(f) = 1 − (f · μ_produto) / ‖μ_produto‖      (‖f‖ = 1)
 *   Distância de cosseno do embedding (MobileNetV3) ao centroide do
 *   produto BOM. Codificação ausente/borrada/deslocada afasta o embedding.
 */
class InspectionModel(
    val backgroundGrid: FloatArray,
    val presenceEnterThreshold: Float,
    val presenceExitThreshold: Float,
    val productCentroid: FloatArray,
    val defectThreshold: Float,
) {

    private val productNorm = VectorMath.norm(productCentroid)

    fun presenceScore(lumaGrid: FloatArray): Float =
        LumaGrid.diffScore(lumaGrid, backgroundGrid)

    fun anomalyScore(embedding: FloatArray): Float =
        1f - VectorMath.dot(embedding, productCentroid) / productNorm
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
