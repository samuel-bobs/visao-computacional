package com.qa.inspecaocodificacao.camera

import android.graphics.Rect
import androidx.camera.core.ImageProxy
import com.qa.inspecaocodificacao.domain.RoiFractions

/**
 * Converte a ROI (frações em coordenadas de EXIBIÇÃO, como o operador vê o
 * preview 4:3) para um Rect em coordenadas do BUFFER do sensor.
 *
 * O preview e a análise usam a MESMA proporção 4:3 com escala FIT
 * (letterbox), então a moldura desenhada na tela corresponde 1:1 ao
 * recorte analisado — correção do desalinhamento observado em campo.
 *
 * Em vez de rotacionar cada frame (cópia 640x480 a 30-60 fps),
 * rotacionamos a ROI; calibração e inferência usam a mesma orientação.
 */
object RoiMapper {

    fun bufferRect(roi: RoiFractions, proxy: ImageProxy): Rect {
        val l: Float
        val t: Float
        val r: Float
        val b: Float
        when (proxy.imageInfo.rotationDegrees) {
            90 -> {
                l = roi.top; t = 1f - roi.right
                r = roi.bottom; b = 1f - roi.left
            }
            180 -> {
                l = 1f - roi.right; t = 1f - roi.bottom
                r = 1f - roi.left; b = 1f - roi.top
            }
            270 -> {
                l = 1f - roi.bottom; t = roi.left
                r = 1f - roi.top; b = roi.right
            }
            else -> {
                l = roi.left; t = roi.top
                r = roi.right; b = roi.bottom
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
