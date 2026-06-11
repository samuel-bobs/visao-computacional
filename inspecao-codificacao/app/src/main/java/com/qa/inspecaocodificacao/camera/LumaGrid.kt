package com.qa.inspecaocodificacao.camera

import android.graphics.Rect
import androidx.camera.core.ImageProxy
import com.qa.inspecaocodificacao.domain.CellMask

/**
 * ESTÁGIO 1 do pipeline: amostragem da grade de luminância da ROI.
 *
 * Lê DIRETO do buffer RGBA do ImageProxy (sem Bitmap, sem alocação).
 * Cada célula da grade 16x16 é a média de um bloco 4x4 no seu centro
 * (4k leituras/frame, ~1 ms) — densidade maior que a v1 (2x2) para
 * garrafas transparentes/sinal fraco.
 *
 * O SCORE de presença (fração de células alteradas) fica no
 * BackgroundModel, que conhece o ruído por célula e a máscara.
 */
object LumaGrid {

    const val GRID = CellMask.GRID
    const val CELLS = CellMask.CELLS

    /** [out] é reutilizado pelo chamador (thread única) — zero alocação. */
    fun sample(proxy: ImageProxy, roi: Rect, out: FloatArray) {
        val plane = proxy.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        val cellW = roi.width().toFloat() / GRID
        val cellH = roi.height().toFloat() / GRID

        var idx = 0
        for (gy in 0 until GRID) {
            // Bloco 4x4 centrado na célula, com clamp nas bordas da ROI.
            val cy = roi.top + ((gy + 0.5f) * cellH).toInt()
            val py = (cy - 1).coerceIn(roi.top, roi.bottom - 4)
            for (gx in 0 until GRID) {
                val cx = roi.left + ((gx + 0.5f) * cellW).toInt()
                val px = (cx - 1).coerceIn(roi.left, roi.right - 4)

                var sum = 0
                for (dy in 0 until 4) {
                    val base = (py + dy) * rowStride + px * pixelStride
                    for (dx in 0 until 4) {
                        val o = base + dx * pixelStride
                        val r = buffer.get(o).toInt() and 0xFF
                        val g = buffer.get(o + 1).toInt() and 0xFF
                        val b = buffer.get(o + 2).toInt() and 0xFF
                        sum += (r + 2 * g + b) shr 2 // luma rápida
                    }
                }
                out[idx++] = sum / 16f
            }
        }
    }
}
