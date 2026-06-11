package com.qa.inspecaocodificacao.camera

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.camera.core.ImageProxy
import com.qa.inspecaocodificacao.domain.CellMask

/**
 * ESTÁGIO 2 (preparação): recorta a ROI direto do buffer RGBA para um
 * Bitmap REUTILIZADO, aplicando a MÁSCARA: células ignoradas são
 * preenchidas com cinza neutro — treino e inferência veem a mesma
 * imagem, então a região mascarada não influencia o anomaly score.
 *
 * Chamado apenas nos frames com garrafa centralizada (gate), nunca nos
 * frames vazios.
 */
class RoiCropper {

    private companion object {
        const val NEUTRAL_GRAY = -0x7F7F80 // ARGB(255, 128, 128, 128)
    }

    private var pixels: IntArray = IntArray(0)
    private var bitmap: Bitmap? = null

    fun crop(proxy: ImageProxy, roi: Rect, mask: CellMask): Bitmap {
        val w = roi.width()
        val h = roi.height()

        if (pixels.size != w * h) {
            pixels = IntArray(w * h)
            bitmap?.recycle()
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        }
        val out = bitmap!!

        val plane = proxy.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val grid = CellMask.GRID

        var idx = 0
        for (y in 0 until h) {
            val cellRow = (y * grid / h) * grid
            var offset = (roi.top + y) * rowStride + roi.left * pixelStride
            for (x in 0 until w) {
                if (mask.isMasked(cellRow + x * grid / w)) {
                    pixels[idx++] = NEUTRAL_GRAY
                } else {
                    val r = buffer.get(offset).toInt() and 0xFF
                    val g = buffer.get(offset + 1).toInt() and 0xFF
                    val b = buffer.get(offset + 2).toInt() and 0xFF
                    pixels[idx++] = -0x1000000 or (r shl 16) or (g shl 8) or b
                }
                offset += pixelStride
            }
        }

        out.setPixels(pixels, 0, w, 0, 0, w, h)
        return out
    }
}
