package com.qa.inspecaocodificacao.camera

import android.graphics.Bitmap
import com.qa.inspecaocodificacao.domain.InspectionConfig

/**
 * Recorta a Região de Interesse do frame.
 *
 * Como a câmera é FIXA, a ROI é definida uma única vez (frações do frame)
 * e vale para todos os frames. Processar só a ROI:
 *  - reduz o custo de inferência (224x224 cobre só a área útil);
 *  - elimina ruído do resto da cena (operadores passando ao fundo, etc.);
 *  - aumenta a sensibilidade, pois 100% dos pixels avaliados são relevantes.
 */
object RoiCropper {

    fun crop(frame: Bitmap, config: InspectionConfig): Bitmap {
        val left = (frame.width * config.roiLeft).toInt().coerceIn(0, frame.width - 2)
        val top = (frame.height * config.roiTop).toInt().coerceIn(0, frame.height - 2)
        val right = (frame.width * config.roiRight).toInt().coerceIn(left + 1, frame.width)
        val bottom = (frame.height * config.roiBottom).toInt().coerceIn(top + 1, frame.height)

        return Bitmap.createBitmap(frame, left, top, right - left, bottom - top)
    }
}
