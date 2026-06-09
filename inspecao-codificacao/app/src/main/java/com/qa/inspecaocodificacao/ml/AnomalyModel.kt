package com.qa.inspecaocodificacao.ml

/**
 * Baseline aprendida na calibração.
 *
 * Matemática:
 * Os embeddings são L2-normalizados, então a distância de cosseno entre o
 * frame f e um centroide μ se reduz a:
 *
 *     d(f, μ) = 1 - (f · μ) / ||μ||        (||f|| = 1)
 *
 * - presenceScore = d(f, μ_vazio):  quanto o frame difere da esteira vazia.
 *   Sobe bruscamente quando uma garrafa entra na ROI (Estado B).
 * - anomalyScore  = d(f, μ_produto): quanto o frame difere do produto BOM.
 *   Codificação borrada, ausente ou deslocada aumenta esta distância.
 */
class AnomalyModel(
    val emptyCentroid: FloatArray,
    val productCentroid: FloatArray,
    val defectThreshold: Float,
) {

    private val emptyNorm = norm(emptyCentroid)
    private val productNorm = norm(productCentroid)

    fun presenceScore(embedding: FloatArray): Float =
        1f - dot(embedding, emptyCentroid) / emptyNorm

    fun anomalyScore(embedding: FloatArray): Float =
        1f - dot(embedding, productCentroid) / productNorm

    companion object {
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
}
