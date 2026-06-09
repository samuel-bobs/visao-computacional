package com.qa.inspecaocodificacao.camera

import android.graphics.Rect
import androidx.camera.core.ImageProxy
import com.qa.inspecaocodificacao.domain.InspectionConfig

/**
 * Converte a ROI (frações em coordenadas de EXIBIÇÃO, como o operador vê o
 * preview) para um Rect em coordenadas do BUFFER do sensor.
 *
 * Otimização chave: em vez de rotacionar cada frame (cópia de 640x480 a
 * 30 fps), rotacionamos a ROI uma única vez. O modelo recebe o recorte na
 * orientação do sensor — como calibração e inferência usam a MESMA
 * orientação, a baseline permanece consistente sem custo por frame.
 */
object RoiMapper {

    fun bufferRect(config: InspectionConfig, proxy: ImageProxy): Rect {
        val l: Float
        val t: Float
        val r: Float
        val b: Float
        when (proxy.imageInfo.rotationDegrees) {
            90 -> {
                l = config.roiTop; t = 1f - config.roiRight
                r = config.roiBottom; b = 1f - config.roiLeft
            }
            180 -> {
                l = 1f - config.roiRight; t = 1f - config.roiBottom
                r = 1f - config.roiLeft; b = 1f - config.roiTop
            }
            270 -> {
                l = 1f - config.roiBottom; t = config.roiLeft
                r = 1f - config.roiTop; b = config.roiRight
            }
            else -> {
                l = config.roiLeft; t = config.roiTop
                r = config.roiRight; b = config.roiBottom
            }
        }

        val w = proxy.width
        val h = proxy.height
        val left = (w * l).toInt().coerceIn(0, w - 2)
        val top = (h * t).toInt().coerceIn(0, h - 2)
        return Rect(
            left,
            top,
            (w * r).toInt().coerceIn(left + 1, w),
            (h * b).toInt().coerceIn(top + 1, h),
        )
    }
}
