package at.techgaisi.farbwerk.ui.actions

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/** Eigene Strich-Icons (24×24) für die Reiter des Aktionen-Panels und die Galerie. */
object ActionIcons {
    private fun icon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.White), strokeLineWidth = 1.7f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block,
            )
        }.build()

    /** Hinzufügen: Plus im Kreis */
    val Add = icon("a_add") {
        moveTo(12f, 3.5f); curveTo(16.7f, 3.5f, 20.5f, 7.3f, 20.5f, 12f)
        curveTo(20.5f, 16.7f, 16.7f, 20.5f, 12f, 20.5f); curveTo(7.3f, 20.5f, 3.5f, 16.7f, 3.5f, 12f)
        curveTo(3.5f, 7.3f, 7.3f, 3.5f, 12f, 3.5f); close()
        moveTo(12f, 8f); lineTo(12f, 16f); moveTo(8f, 12f); lineTo(16f, 12f)
    }

    /** Leinwand: Rahmen mit Eckmarken */
    val Canvas = icon("a_canvas") {
        moveTo(6f, 6f); lineTo(18f, 6f); lineTo(18f, 18f); lineTo(6f, 18f); close()
        moveTo(3f, 6f); lineTo(5f, 6f); moveTo(6f, 3f); lineTo(6f, 5f)
        moveTo(19f, 18f); lineTo(21f, 18f); moveTo(18f, 19f); lineTo(18f, 21f)
    }

    /** Teilen: Kasten mit Pfeil nach oben */
    val Share = icon("a_share") {
        moveTo(12f, 3.5f); lineTo(12f, 14f)
        moveTo(8.5f, 7f); lineTo(12f, 3.5f); lineTo(15.5f, 7f)
        moveTo(8f, 10f); lineTo(5.5f, 10f); lineTo(5.5f, 20.5f); lineTo(18.5f, 20.5f); lineTo(18.5f, 10f); lineTo(16f, 10f)
    }

    /** Video: Filmkamera */
    val Video = icon("a_video") {
        moveTo(3.5f, 7f); lineTo(15f, 7f); lineTo(15f, 17f); lineTo(3.5f, 17f); close()
        moveTo(15f, 10.5f); lineTo(20.5f, 7.5f); lineTo(20.5f, 16.5f); lineTo(15f, 13.5f)
    }

    /** Einstellungen: Schieberegler */
    val Settings = icon("a_settings") {
        moveTo(4f, 7f); lineTo(20f, 7f); moveTo(4f, 12f); lineTo(20f, 12f); moveTo(4f, 17f); lineTo(20f, 17f)
        moveTo(9f, 5f); lineTo(9f, 9f); moveTo(15f, 10f); lineTo(15f, 14f); moveTo(7f, 15f); lineTo(7f, 19f)
    }

    /** Hilfe: Fragezeichen im Kreis */
    val Help = icon("a_help") {
        moveTo(12f, 3.5f); curveTo(16.7f, 3.5f, 20.5f, 7.3f, 20.5f, 12f)
        curveTo(20.5f, 16.7f, 16.7f, 20.5f, 12f, 20.5f); curveTo(7.3f, 20.5f, 3.5f, 16.7f, 3.5f, 12f)
        curveTo(3.5f, 7.3f, 7.3f, 3.5f, 12f, 3.5f); close()
        moveTo(9.5f, 9.5f); curveTo(9.5f, 6.5f, 14.5f, 6.5f, 14.5f, 9.5f)
        curveTo(14.5f, 11.5f, 12f, 11.5f, 12f, 13.8f)
        moveTo(12f, 16.6f); lineTo(12f, 16.8f)
    }

    /** Zurück-Pfeil */
    val Back = icon("a_back") { moveTo(14.5f, 5f); lineTo(7.5f, 12f); lineTo(14.5f, 19f) }

    /** Weiter-Pfeil (Untermenü) */
    val Chevron = icon("a_chev") { moveTo(9.5f, 6f); lineTo(15.5f, 12f); lineTo(9.5f, 18f) }

    /** Foto/Bild */
    val Photo = icon("a_photo") {
        moveTo(3.5f, 5.5f); lineTo(20.5f, 5.5f); lineTo(20.5f, 18.5f); lineTo(3.5f, 18.5f); close()
        moveTo(3.5f, 16f); lineTo(9f, 11f); lineTo(13f, 15f); lineTo(15.5f, 12.5f); lineTo(20.5f, 17f)
        moveTo(15.5f, 8.5f); lineTo(15.6f, 8.5f)
    }

    /** Importieren: Pfeil in Ablage */
    val Import = icon("a_import") {
        moveTo(12f, 3.5f); lineTo(12f, 14f)
        moveTo(8.5f, 10.5f); lineTo(12f, 14f); lineTo(15.5f, 10.5f)
        moveTo(4f, 14.5f); lineTo(4f, 20f); lineTo(20f, 20f); lineTo(20f, 14.5f)
    }

    /** Plus ohne Kreis */
    val Plus = icon("a_plus") { moveTo(12f, 4.5f); lineTo(12f, 19.5f); moveTo(4.5f, 12f); lineTo(19.5f, 12f) }

    /** Häkchen */
    val Check = icon("a_check") { moveTo(5f, 12.5f); lineTo(10f, 17.5f); lineTo(19f, 7f) }

    /** Papierkorb */
    val Trash = icon("a_trash") {
        moveTo(4.5f, 6.5f); lineTo(19.5f, 6.5f)
        moveTo(9.5f, 6.5f); lineTo(9.5f, 4f); lineTo(14.5f, 4f); lineTo(14.5f, 6.5f)
        moveTo(6.5f, 6.5f); lineTo(7.5f, 20f); lineTo(16.5f, 20f); lineTo(17.5f, 6.5f)
        moveTo(10f, 10f); lineTo(10f, 16.5f); moveTo(14f, 10f); lineTo(14f, 16.5f)
    }

    /** Duplizieren: zwei versetzte Rechtecke */
    val Duplicate = icon("a_dup") {
        moveTo(8f, 8f); lineTo(20f, 8f); lineTo(20f, 20f); lineTo(8f, 20f); close()
        moveTo(16f, 5f); lineTo(16f, 4f); lineTo(4f, 4f); lineTo(4f, 16f); lineTo(5f, 16f)
    }
}
