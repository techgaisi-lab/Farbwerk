package at.techgaisi.farbwerk

/** Ressourcen-IDs der App für den Desktop (Schriften liegen als Klassenpfad-Ressourcen unter font/). */
object R {
    object font {
        const val inter_regular = 0x7f090000
        const val inter_medium = 0x7f090001
        const val inter_semibold = 0x7f090002
        const val inter_bold = 0x7f090003
    }

    object string {
        const val app_name = 0x7f100000
    }

    private val fontNames = mapOf(
        font.inter_regular to "inter_regular",
        font.inter_medium to "inter_medium",
        font.inter_semibold to "inter_semibold",
        font.inter_bold to "inter_bold",
    )

    fun fontName(id: Int): String = fontNames[id] ?: throw android.content.res.Resources.NotFoundException("Schrift #$id")

    fun stringValue(id: Int): String = when (id) {
        string.app_name -> "Farbwerk"
        else -> throw android.content.res.Resources.NotFoundException("Text #$id")
    }
}
