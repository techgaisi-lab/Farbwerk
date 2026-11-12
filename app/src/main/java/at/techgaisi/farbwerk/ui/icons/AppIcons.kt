package at.techgaisi.farbwerk.ui.icons

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * Eigene Strich-Icons für die Werkzeugleiste (24x24, 1.8 Strichstärke).
 */
object AppIcons {
    private fun icon(name: String, block: PathBuilder.() -> Unit, fill: Boolean = false): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                fill = if (fill) SolidColor(Color.White) else null,
                stroke = if (fill) null else SolidColor(Color.White),
                strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }.build()

    /** Pinsel: schräger Stiel mit Spitze */
    val Paint = icon("paint", {
        moveTo(19.5f, 4.5f); lineTo(10.5f, 13.5f)
        moveTo(10.5f, 13.5f); curveTo(8f, 13f, 5.5f, 14.5f, 5.5f, 17f)
        curveTo(5.5f, 18.5f, 4.8f, 19.3f, 3.8f, 19.8f)
        curveTo(6.5f, 20.6f, 11.5f, 19.5f, 10.5f, 13.5f)
    })

    /** Wischfinger: Fingerkuppe mit Bewegungslinien */
    val Smudge = icon("smudge", {
        moveTo(12f, 21f); curveTo(8.5f, 21f, 7f, 18f, 7f, 15f)
        lineTo(7f, 9.5f); curveTo(7f, 8.4f, 8.8f, 8.4f, 8.8f, 9.5f)
        lineTo(8.8f, 12f); lineTo(8.8f, 5f)
        curveTo(8.8f, 3.6f, 11f, 3.6f, 11f, 5f); lineTo(11f, 11f)
        lineTo(11f, 9.5f); curveTo(11f, 8.3f, 13.2f, 8.3f, 13.2f, 9.5f); lineTo(13.2f, 11f)
        curveTo(13.2f, 9.9f, 15.4f, 9.9f, 15.4f, 11f); lineTo(15.4f, 15.5f)
        curveTo(15.4f, 18.5f, 14.5f, 21f, 12f, 21f)
        moveTo(18.5f, 6f); lineTo(21f, 6f)
        moveTo(18f, 9f); lineTo(21f, 9f)
    })

    /** Radierer: schräger Block */
    val Erase = icon("erase", {
        moveTo(14.5f, 4f); lineTo(20.5f, 10f); lineTo(11f, 19.5f); lineTo(6.5f, 19.5f)
        lineTo(3.5f, 16.5f); close()
        moveTo(9f, 9.5f); lineTo(15f, 15.5f)
        moveTo(11f, 19.5f); lineTo(20.5f, 19.5f)
    })

    /** Ebenen: drei versetzte Rauten */
    val Layers = icon("layers", {
        moveTo(12f, 3.5f); lineTo(21f, 8f); lineTo(12f, 12.5f); lineTo(3f, 8f); close()
        moveTo(3f, 12f); lineTo(12f, 16.5f); lineTo(21f, 12f)
        moveTo(3f, 16f); lineTo(12f, 20.5f); lineTo(21f, 16f)
    })

    /** Aktionen: Zahnrad-artiger Schraubenschlüssel */
    val Actions = icon("actions", {
        moveTo(14.5f, 4f); curveTo(17.5f, 3.2f, 20.5f, 5.8f, 19.8f, 9f)
        lineTo(17f, 8.5f); lineTo(15.5f, 7f); close()
        moveTo(19.8f, 9f); curveTo(19.2f, 11f, 17f, 12.2f, 15f, 11.6f)
        lineTo(7f, 19.6f); curveTo(6f, 20.6f, 4.4f, 19f, 5.4f, 18f)
        lineTo(13.4f, 10f); curveTo(12.8f, 8f, 13.2f, 5f, 14.5f, 4f)
    })

    /** Anpassungen: Zauberstab mit Funken */
    val Adjust = icon("adjust", {
        moveTo(4f, 20f); lineTo(14.5f, 9.5f)
        moveTo(13f, 8f); lineTo(16f, 11f)
        moveTo(18f, 3f); lineTo(18f, 6f); moveTo(16.5f, 4.5f); lineTo(19.5f, 4.5f)
        moveTo(20.5f, 9f); lineTo(20.5f, 11f); moveTo(19.5f, 10f); lineTo(21.5f, 10f)
        moveTo(10f, 3.5f); lineTo(10f, 5.5f); moveTo(9f, 4.5f); lineTo(11f, 4.5f)
    })

    /** Auswahl: geschwungene Schleife */
    val Select = icon("select", {
        moveTo(6f, 15f); curveTo(2f, 11f, 6f, 4f, 13f, 4.5f)
        curveTo(19f, 5f, 21f, 10f, 18f, 13.5f)
        curveTo(15.5f, 16.5f, 10f, 16.8f, 8f, 15.2f)
        moveTo(8f, 15.2f); curveTo(6.5f, 17f, 7.5f, 19.5f, 9.5f, 20f)
    })

    /** Transformieren: Pfeilzeiger */
    val Transform = icon("transform", {
        moveTo(5f, 4f); lineTo(19f, 10.5f); lineTo(12.5f, 12.5f); lineTo(10.5f, 19f); close()
        moveTo(12.5f, 12.5f); lineTo(18.5f, 18.5f)
    })

    val Undo = icon("undo", {
        moveTo(9f, 6f); lineTo(4.5f, 10.5f); lineTo(9f, 15f)
        moveTo(4.5f, 10.5f); lineTo(14f, 10.5f); curveTo(17.5f, 10.5f, 19.5f, 12.5f, 19.5f, 15f)
        curveTo(19.5f, 17.5f, 17.5f, 19f, 15f, 19f)
    })

    val Redo = icon("redo", {
        moveTo(15f, 6f); lineTo(19.5f, 10.5f); lineTo(15f, 15f)
        moveTo(19.5f, 10.5f); lineTo(10f, 10.5f); curveTo(6.5f, 10.5f, 4.5f, 12.5f, 4.5f, 15f)
        curveTo(4.5f, 17.5f, 6.5f, 19f, 9f, 19f)
    })

    /** Vollbild verlassen: zwei Winkel, die nach außen zeigen (Oberfläche zurückholen). */
    val FullscreenExit = icon("fullscreenExit", {
        moveTo(4.5f, 9.5f); lineTo(4.5f, 4.5f); lineTo(9.5f, 4.5f)
        moveTo(4.5f, 4.5f); lineTo(10f, 10f)
        moveTo(19.5f, 14.5f); lineTo(19.5f, 19.5f); lineTo(14.5f, 19.5f)
        moveTo(19.5f, 19.5f); lineTo(14f, 14f)
    })

    val Plus = icon("plus", {
        moveTo(12f, 5f); lineTo(12f, 19f)
        moveTo(5f, 12f); lineTo(19f, 12f)
    })

    val Minus = icon("minus", {
        moveTo(5f, 12f); lineTo(19f, 12f)
    })

    /** Warnsymbol (Dreieck mit Ausrufezeichen) für Gesten-Konflikte. */
    val Warning = icon("warning", {
        moveTo(12f, 3.5f); lineTo(21.5f, 20f); lineTo(2.5f, 20f); close()
        moveTo(12f, 9.5f); lineTo(12f, 14f)
        moveTo(12f, 17f); lineTo(12f, 17.2f)
    })

    /** Pipette (Tropfer) – eigene Zeichnung. */
    val Eyedropper = icon("eyedropper", {
        moveTo(14f, 6f); lineTo(18f, 10f)
        moveTo(16f, 8f); lineTo(7f, 17f); lineTo(4.5f, 19.5f)
        moveTo(15f, 5f); curveTo(16.5f, 3.5f, 18.5f, 3.5f, 19.5f, 4.5f)
        curveTo(20.5f, 5.5f, 20.5f, 7.5f, 19f, 9f)
    })
}
