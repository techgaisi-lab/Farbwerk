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
 * Eigene Symbole von Farbwerk (selbst gezeichnet, Viewport 24 × 24).
 *
 * Die Strichstärke ist je Symbol so gewählt, dass sie in der vorgesehenen Darstellungsgröße der Optik-Vorgabe
 * entspricht (UI_SPEC §1/§2):
 *  - linke Leiste: 22 dp Darstellung, 1,8 dp Strich → 1,96 Viewport-Einheiten,
 *  - rechte Leiste: 24 dp Darstellung, 1,8 dp Strich → 1,8 Einheiten (Werkzeuge teils gefüllt),
 *  - Seitenleiste Rückgängig/Wiederholen: 18 dp Darstellung, 1,6 dp Strich → 2,13 Einheiten,
 *  - „+“ in Popover-Titeln: 22 dp, dünn 1,5 dp → 1,64 Einheiten.
 * Alle Pfade sind weiß; die Tönung erfolgt über `Icon(tint = …)`.
 */
object AppIcons {
    private const val STROKE_LEFT = 1.8f * 24f / 22f
    private const val STROKE_RIGHT = 1.8f
    private const val STROKE_SIDEBAR = 1.6f * 24f / 18f
    private const val STROKE_THIN = 1.5f * 24f / 22f

    /** Baut ein Symbol aus mehreren Pfaden (Strich- und Füllpfade gemischt). */
    private class IconScope(private val builder: ImageVector.Builder, private val defaultStroke: Float) {
        fun stroke(width: Float = defaultStroke, block: PathBuilder.() -> Unit) {
            builder.path(
                fill = null,
                stroke = SolidColor(Color.White),
                strokeLineWidth = width,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
                pathBuilder = block,
            )
        }

        fun fill(block: PathBuilder.() -> Unit) {
            builder.path(fill = SolidColor(Color.White), pathBuilder = block)
        }
    }

    private fun icon(name: String, stroke: Float, block: IconScope.() -> Unit): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        IconScope(b, stroke).block()
        return b.build()
    }

    /** Einfaches Strich-Symbol (Kompatibilität). */
    private fun strokeIcon(name: String, stroke: Float, block: PathBuilder.() -> Unit) =
        icon(name, stroke) { stroke(block = block) }

    // ======================================================================
    // Obere Leiste links (22 dp)
    // ======================================================================

    /** Aktionen: Schraubenschlüssel, schräg von links unten nach rechts oben, Maulöffnung oben rechts. */
    val Actions = icon("actions", STROKE_LEFT) {
        stroke {
            // Kopf (Kreis um 16/8, r 4,4) mit Maul nach rechts oben, dann Stiel nach links unten
            moveTo(14.5f, 12.13f)
            arcTo(4.4f, 4.4f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 20.05f, y1 = 6.28f)
            lineTo(17.6f, 6.4f)
            lineTo(17.72f, 3.95f)
            arcTo(4.4f, 4.4f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 11.87f, y1 = 9.5f)
            lineTo(4.4f, 16.9f)
            arcTo(1.55f, 1.55f, 0f, isMoreThanHalf = false, isPositiveArc = false, x1 = 6.6f, y1 = 19.1f)
            close()
        }
    }

    /** Anpassungen: Zauberstab mit zwei Funkelsternen. */
    val Adjust = icon("adjust", STROKE_LEFT) {
        stroke {
            moveTo(4.5f, 19.5f); lineTo(14.2f, 9.8f)
        }
        // großer Funke (vierzackig, gefüllt) an der Stabspitze
        fill {
            moveTo(17.2f, 3.2f)
            quadTo(17.6f, 6.2f, 20.6f, 6.6f)
            quadTo(17.6f, 7.0f, 17.2f, 10.0f)
            quadTo(16.8f, 7.0f, 13.8f, 6.6f)
            quadTo(16.8f, 6.2f, 17.2f, 3.2f)
            close()
        }
        // kleiner Funke
        fill {
            moveTo(9.6f, 3.0f)
            quadTo(9.85f, 4.55f, 11.4f, 4.8f)
            quadTo(9.85f, 5.05f, 9.6f, 6.6f)
            quadTo(9.35f, 5.05f, 7.8f, 4.8f)
            quadTo(9.35f, 4.55f, 9.6f, 3.0f)
            close()
        }
    }

    /** Auswahl: geschwungene S-Schleife. */
    val Select = icon("select", STROKE_LEFT) {
        stroke {
            moveTo(16.8f, 5.6f)
            curveTo(14.2f, 3.6f, 8.0f, 4.0f, 8.2f, 7.9f)
            curveTo(8.4f, 11.4f, 15.9f, 11.0f, 15.9f, 15.4f)
            curveTo(15.9f, 19.6f, 9.6f, 20.6f, 6.8f, 18.4f)
        }
    }

    /** Transformieren: schräger Pfeil nach rechts oben mit gefüllter Spitze. */
    val Transform = icon("transform", STROKE_LEFT) {
        stroke {
            moveTo(5.5f, 18.5f); lineTo(14.2f, 9.8f)
        }
        fill {
            moveTo(19.3f, 4.7f)
            lineTo(17.2f, 13.0f)
            lineTo(11.0f, 6.8f)
            close()
        }
    }

    // ======================================================================
    // Obere Leiste rechts (24 dp)
    // ======================================================================

    /** Malen: Pinsel schräg, Stiel rechts oben, gefüllte Spitze links unten. */
    val Paint = icon("paint", STROKE_RIGHT) {
        stroke {
            moveTo(20.2f, 3.8f); lineTo(12.6f, 11.4f)
        }
        fill {
            moveTo(10.6f, 11.0f)
            curveTo(13.4f, 11.6f, 13.2f, 15.2f, 10.9f, 16.8f)
            curveTo(8.8f, 18.2f, 6.2f, 19.0f, 3.6f, 20.4f)
            curveTo(4.7f, 18.0f, 5.4f, 15.4f, 6.9f, 13.4f)
            curveTo(7.9f, 12.0f, 9.2f, 10.8f, 10.6f, 11.0f)
            close()
        }
    }

    /** Wischen: ausgestreckter Zeigefinger (Umriss) mit Handballen, leicht schräg. */
    val Smudge = icon("smudge", STROKE_RIGHT) {
        stroke {
            // Zeigefinger
            moveTo(9.6f, 13.2f)
            lineTo(5.6f, 6.4f)
            curveTo(5.0f, 5.3f, 6.4f, 4.2f, 7.3f, 5.2f)
            lineTo(11.6f, 11.0f)
            // Fingerknöchel der übrigen Finger und Handballen
            curveTo(12.0f, 9.8f, 13.6f, 9.8f, 14.0f, 10.9f)
            curveTo(14.5f, 9.9f, 16.0f, 10.0f, 16.3f, 11.2f)
            curveTo(16.9f, 10.4f, 18.3f, 10.8f, 18.4f, 12.0f)
            lineTo(18.9f, 15.6f)
            curveTo(19.3f, 18.8f, 16.6f, 20.9f, 13.6f, 20.6f)
            curveTo(11.2f, 20.4f, 9.8f, 18.8f, 8.6f, 17.2f)
            lineTo(6.6f, 14.6f)
            curveTo(6.0f, 13.7f, 7.2f, 12.6f, 8.1f, 13.4f)
            lineTo(9.6f, 14.8f)
        }
    }

    /** Radieren: schräger Radiergummi, unteres Drittel gefüllt. */
    val Erase = icon("erase", STROKE_RIGHT) {
        stroke {
            moveTo(14.2f, 3.9f)
            lineTo(20.1f, 9.8f)
            lineTo(11.2f, 18.7f)
            quadTo(10.8f, 19.1f, 10.2f, 19.1f)
            lineTo(7.6f, 19.1f)
            quadTo(7.0f, 19.1f, 6.6f, 18.7f)
            lineTo(4.3f, 16.4f)
            quadTo(3.9f, 16.0f, 4.3f, 15.6f)
            close()
            moveTo(12.6f, 19.1f); lineTo(19.6f, 19.1f)
        }
        fill {
            moveTo(8.4f, 9.6f)
            lineTo(14.4f, 15.6f)
            lineTo(11.2f, 18.7f)
            quadTo(10.8f, 19.1f, 10.2f, 19.1f)
            lineTo(7.6f, 19.1f)
            quadTo(7.0f, 19.1f, 6.6f, 18.7f)
            lineTo(4.3f, 16.4f)
            quadTo(3.9f, 16.0f, 4.3f, 15.6f)
            close()
        }
    }

    /** Ebenen: zwei versetzte Quadrate – hinten Umriss, vorne gefüllt (mit Lücke dazwischen). */
    val Layers = icon("layers", STROKE_RIGHT) {
        stroke {
            moveTo(8.2f, 5.8f)
            lineTo(8.2f, 5.2f)
            quadTo(8.2f, 3.2f, 10.2f, 3.2f)
            lineTo(18.8f, 3.2f)
            quadTo(20.8f, 3.2f, 20.8f, 5.2f)
            lineTo(20.8f, 13.8f)
            quadTo(20.8f, 15.8f, 18.8f, 15.8f)
            lineTo(18.2f, 15.8f)
        }
        fill {
            moveTo(5.2f, 8.2f)
            lineTo(13.8f, 8.2f)
            quadTo(15.8f, 8.2f, 15.8f, 10.2f)
            lineTo(15.8f, 18.8f)
            quadTo(15.8f, 20.8f, 13.8f, 20.8f)
            lineTo(5.2f, 20.8f)
            quadTo(3.2f, 20.8f, 3.2f, 18.8f)
            lineTo(3.2f, 10.2f)
            quadTo(3.2f, 8.2f, 5.2f, 8.2f)
            close()
        }
    }

    // ======================================================================
    // Seitenleiste (18 dp)
    // ======================================================================

    /** Rückgängig: gebogener Pfeil nach links. */
    val Undo = strokeIcon("undo", STROKE_SIDEBAR) {
        moveTo(8.5f, 5.5f); lineTo(4.0f, 10.0f); lineTo(8.5f, 14.5f)
        moveTo(4.0f, 10.0f); lineTo(14.0f, 10.0f)
        curveTo(17.6f, 10.0f, 20.0f, 12.3f, 20.0f, 15.0f)
        curveTo(20.0f, 17.7f, 17.6f, 19.5f, 14.8f, 19.5f)
        lineTo(11.5f, 19.5f)
    }

    /** Wiederholen: gebogener Pfeil nach rechts. */
    val Redo = strokeIcon("redo", STROKE_SIDEBAR) {
        moveTo(15.5f, 5.5f); lineTo(20.0f, 10.0f); lineTo(15.5f, 14.5f)
        moveTo(20.0f, 10.0f); lineTo(10.0f, 10.0f)
        curveTo(6.4f, 10.0f, 4.0f, 12.3f, 4.0f, 15.0f)
        curveTo(4.0f, 17.7f, 6.4f, 19.5f, 9.2f, 19.5f)
        lineTo(12.5f, 19.5f)
    }

    // ======================================================================
    // Allgemein
    // ======================================================================

    /** Vollbild verlassen: zwei Winkel, die nach außen zeigen (Oberfläche zurückholen). */
    val FullscreenExit = strokeIcon("fullscreenExit", STROKE_RIGHT) {
        moveTo(4.5f, 9.5f); lineTo(4.5f, 4.5f); lineTo(9.5f, 4.5f)
        moveTo(4.5f, 4.5f); lineTo(10f, 10f)
        moveTo(19.5f, 14.5f); lineTo(19.5f, 19.5f); lineTo(14.5f, 19.5f)
        moveTo(19.5f, 19.5f); lineTo(14f, 14f)
    }

    /** Plus, dünn (Popover-Titel 22 dp / 1,5 dp). */
    val Plus = strokeIcon("plus", STROKE_THIN) {
        moveTo(12f, 3.5f); lineTo(12f, 20.5f)
        moveTo(3.5f, 12f); lineTo(20.5f, 12f)
    }

    val Minus = strokeIcon("minus", STROKE_THIN) {
        moveTo(3.5f, 12f); lineTo(20.5f, 12f)
    }

    /** Schließen (×) für schwebende Fenster. */
    val Close = strokeIcon("close", 2.4f) {
        moveTo(7f, 7f); lineTo(17f, 17f)
        moveTo(17f, 7f); lineTo(7f, 17f)
    }

    /** Warnsymbol (Dreieck mit Ausrufezeichen) für Gesten-Konflikte. */
    val Warning = strokeIcon("warning", STROKE_RIGHT) {
        moveTo(12f, 3.5f); lineTo(21.5f, 20f); lineTo(2.5f, 20f); close()
        moveTo(12f, 9.5f); lineTo(12f, 14f)
        moveTo(12f, 17f); lineTo(12f, 17.2f)
    }

    /** Pipette (Tropfer). */
    val Eyedropper = strokeIcon("eyedropper", STROKE_RIGHT) {
        moveTo(14f, 6f); lineTo(18f, 10f)
        moveTo(16f, 8f); lineTo(7f, 17f); lineTo(4.5f, 19.5f)
        moveTo(15f, 5f); curveTo(16.5f, 3.5f, 18.5f, 3.5f, 19.5f, 4.5f)
        curveTo(20.5f, 5.5f, 20.5f, 7.5f, 19f, 9f)
    }
}
