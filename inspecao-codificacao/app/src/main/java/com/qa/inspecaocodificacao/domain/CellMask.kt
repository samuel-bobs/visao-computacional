package com.qa.inspecaocodificacao.domain

/**
 * Máscara de exclusão por células da grade de análise (16x16 sobre a ROI).
 *
 * Pedido de campo: dentro da janela de medição existem áreas que atrapalham
 * (reflexos, partes móveis da máquina, respingos). O usuário "pinta" essas
 * células no modo de ajuste e elas são ignoradas:
 *  - na PRESENÇA: células mascaradas ficam fora da fração de células alteradas;
 *  - na ANOMALIA: a região mascarada é preenchida com cinza neutro no recorte
 *    enviado ao modelo — treino e inferência veem a mesma coisa.
 *
 * true = célula IGNORADA.
 */
class CellMask(val cells: BooleanArray) {

    companion object {
        const val GRID = 16
        const val CELLS = GRID * GRID

        fun empty() = CellMask(BooleanArray(CELLS))

        fun fromString(s: String?): CellMask {
            if (s == null || s.length != CELLS) return empty()
            return CellMask(BooleanArray(CELLS) { s[it] == '1' })
        }

        fun serialize(masked: Set<Int>): String =
            String(CharArray(CELLS) { if (it in masked) '1' else '0' })
    }

    fun isMasked(index: Int): Boolean = cells[index]

    val activeCellCount: Int get() = cells.count { !it }

    fun serialize(): String = String(CharArray(CELLS) { if (cells[it]) '1' else '0' })

    /**
     * A máscara é editada em coordenadas de EXIBIÇÃO; o pipeline lê o buffer
     * na orientação do SENSOR. Converte célula a célula pela mesma rotação
     * usada no RoiMapper.
     */
    fun toBufferOrientation(rotationDegrees: Int): CellMask {
        if (rotationDegrees == 0) return this
        val out = BooleanArray(CELLS)
        for (by in 0 until GRID) {
            for (bx in 0 until GRID) {
                val (dx, dy) = when (rotationDegrees) {
                    90 -> (GRID - 1 - by) to bx
                    180 -> (GRID - 1 - bx) to (GRID - 1 - by)
                    270 -> by to (GRID - 1 - bx)
                    else -> bx to by
                }
                out[by * GRID + bx] = cells[dy * GRID + dx]
            }
        }
        return CellMask(out)
    }
}
