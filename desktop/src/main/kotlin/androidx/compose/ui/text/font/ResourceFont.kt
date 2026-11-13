package androidx.compose.ui.text.font

/**
 * Android-Überladung Font(resId, weight, style): lädt die TTF aus den Klassenpfad-Ressourcen
 * (app/src/main/res/font → "font/<name>.ttf").
 */
fun Font(resId: Int, weight: FontWeight = FontWeight.Normal, style: FontStyle = FontStyle.Normal): Font =
    androidx.compose.ui.text.platform.Font("font/" + at.techgaisi.farbwerk.R.fontName(resId) + ".ttf", weight, style)
