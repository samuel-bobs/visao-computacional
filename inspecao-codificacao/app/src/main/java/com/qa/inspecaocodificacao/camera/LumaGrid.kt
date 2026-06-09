package com.qa.inspecaocodificacao.camera

import android.graphics.Rect
import androidx.camera.core.ImageProxy

/**
 * ESTÁGIO 1 do pipeline: presença por diferença de luminância.
 *
 * A 18.000 garrafas/hora (5/s) a contagem precisa rodar em TODO frame de
 * 30 fps — o TFLite (~10-15 ms no M31) não cabe nesse orçamento. Esta
 * classe amostra uma grade 16x16 de luma da ROI DIRETO do buffer RGBA do
 * ImageProxy (sem criar Bitmap, sem alocação): ~1k leituras, <0,5 ms.
 *
 * O score de presença é a diferença média absoluta entre a grade do frame
 * e a grade de referência do fundo (aprendida na fase "esteira vazia").
 */
object LumaGrid {

    const val GRID = 16
    const val CELLS = GRID * GRID

    /**
     * Amostra a grade de luma da ROI. [out] é reutilizado pelo chamador
     * (thread única de análise) — zero alocação por frame.
     * Requer OUTPUT_IMAGE_FORMAT_RGBA_8888 no ImageAnalysis.
     */
    fun sample(proxy: ImageProxy, roi: Rect, out: FloatArray) {
        val plane = proxy.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        val cellW = roi.width().toFloat() / GRID
        val cellH = roi.height().toFloat() / GRID

        var idx = 0
        for (gy in 0 until GRID) {
            val py = roi.top + ((gy + 0.5f) * cellH).toInt()
            for (gx in 0 until GRID) {
                val px = roi.left + ((gx + 0.5f) * cellW).toInt()
                // Média 2x2 no centro da célula: robustez a ruído de sensor.
                var sum = 0
                for (dy in 0..1) {
                    val base = (py + dy) * rowStride + px * pixelStride
                    for (dx in 0..1) {
                        val o = base + dx * pixelStride
                        val rCh = buffer.get(o).toInt() and 0xFF
                        val gCh = buffer.get(o + 1).toInt() and 0xFF
                        val bCh = buffer.get(o + 2).toInt() and 0xFF
                        sum += (rCh + 2 * gCh + bCh) shr 2 // luma rápida
                    }
                }
                out[idx++] = sum / 4f
            }
        }
    }

    /** Diferença média absoluta normalizada [0..1] entre grade e referência. */
    fun diffScore(grid: FloatArray, reference: FloatArray): Float {
        var sum = 0f
        for (i in grid.indices) {
            val d = grid[i] - reference[i]
            sum += if (d >= 0) d else -d
        }
        return sum / (grid.size * 255f)
    }
}
