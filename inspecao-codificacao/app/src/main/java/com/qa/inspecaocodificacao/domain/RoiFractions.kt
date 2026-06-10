package com.qa.inspecaocodificacao.domain

/**
 * Janela de medição (ROI) em frações do frame de EXIBIÇÃO (4:3, como o
 * operador vê o preview). Ajustável em campo pelo usuário (modo QA Admin)
 * para acomodar diferentes formatos de garrafa, e persistida em DataStore.
 */
data class RoiFractions(
    val left: Float = 0.35f,
    val top: Float = 0.25f,
    val right: Float = 0.65f,
    val bottom: Float = 0.75f,
) {
    companion object {
        const val MIN_SIZE = 0.08f
    }

    /** Garante ROI dentro do frame e com tamanho mínimo utilizável. */
    fun clamped(): RoiFractions {
        val l = left.coerceIn(0f, 1f - MIN_SIZE)
        val t = top.coerceIn(0f, 1f - MIN_SIZE)
        return RoiFractions(
            left = l,
            top = t,
            right = right.coerceIn(l + MIN_SIZE, 1f),
            bottom = bottom.coerceIn(t + MIN_SIZE, 1f),
        )
    }

    fun width() = right - left
    fun height() = bottom - top
}
