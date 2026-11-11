package at.techgaisi.farbwerk.ui.color

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import at.techgaisi.farbwerk.color.ColorMath
import at.techgaisi.farbwerk.ui.theme.LocalUi
import kotlin.math.roundToInt

/** Klassisch: Sättigung/Helligkeit-Quadrat plus Farbton-, Sättigungs- und Helligkeitsregler. */
@Composable
fun ClassicTab(model: HsvModel) {
    val ui = LocalUi.current
    val hueColor = ColorMath.hsvToArgb(model.h, 1f, 1f).toUiColor()
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1.25f)
                .colorGestures(
                    onPress = { p, s ->
                        model.set(
                            s = (p.x / s.width).coerceIn(0f, 1f),
                            v = 1f - (p.y / s.height).coerceIn(0f, 1f)
                        )
                    },
                    onRelease = { model.release() },
                )
        ) {
            val cr = CornerRadius(8.dp.toPx())
            drawRoundRect(Brush.horizontalGradient(listOf(Color.White, hueColor)), cornerRadius = cr)
            drawRoundRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)), cornerRadius = cr)
            drawRoundRect(ui.divider, cornerRadius = cr, style = Stroke(1.dp.toPx()))
            drawKnob(Offset(model.s * size.width, (1f - model.v) * size.height), 11.dp.toPx(), model.argb.toUiColor())
        }
        Spacer(Modifier.height(14.dp))
        LabeledSlider(
            "Farbton", "${model.h.roundToInt()}°", model.h / 360f,
            Brush.horizontalGradient(HueColors), hueColor,
            onChange = { model.set(h = it * 360f) }, onRelease = { model.release() }
        )
        LabeledSlider(
            "Sättigung", "${(model.s * 100).roundToInt()} %", model.s,
            Brush.horizontalGradient(listOf(
                ColorMath.hsvToArgb(model.h, 0f, model.v).toUiColor(),
                ColorMath.hsvToArgb(model.h, 1f, model.v).toUiColor()
            )),
            model.argb.toUiColor(),
            onChange = { model.set(s = it) }, onRelease = { model.release() }
        )
        LabeledSlider(
            "Helligkeit", "${(model.v * 100).roundToInt()} %", model.v,
            Brush.horizontalGradient(listOf(Color.Black, ColorMath.hsvToArgb(model.h, model.s, 1f).toUiColor())),
            model.argb.toUiColor(),
            onChange = { model.set(v = it) }, onRelease = { model.release() }
        )
    }
}
