package at.techgaisi.farbwerk.ui.color

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.color.ColorMath
import at.techgaisi.farbwerk.ui.theme.LocalUi
import kotlin.math.roundToInt

/** Werte: HSB-Regler, RGB-Regler und editierbarer Hex-Code. */
@Composable
fun ValuesTab(model: HsvModel) {
    val ui = LocalUi.current
    val argb = model.argb
    val r = ColorMath.red(argb); val g = ColorMath.green(argb); val b = ColorMath.blue(argb)

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        SectionTitle("HSB")
        LabeledSlider(
            "Farbton", "${model.h.roundToInt()}°", model.h / 360f,
            Brush.horizontalGradient(HueColors), ColorMath.hsvToArgb(model.h, 1f, 1f).toUiColor(),
            onChange = { model.set(h = it * 360f) }, onRelease = { model.release() }
        )
        LabeledSlider(
            "Sättigung", "${(model.s * 100).roundToInt()} %", model.s,
            Brush.horizontalGradient(listOf(
                ColorMath.hsvToArgb(model.h, 0f, model.v).toUiColor(),
                ColorMath.hsvToArgb(model.h, 1f, model.v).toUiColor()
            )),
            argb.toUiColor(), onChange = { model.set(s = it) }, onRelease = { model.release() }
        )
        LabeledSlider(
            "Helligkeit", "${(model.v * 100).roundToInt()} %", model.v,
            Brush.horizontalGradient(listOf(Color.Black, ColorMath.hsvToArgb(model.h, model.s, 1f).toUiColor())),
            argb.toUiColor(), onChange = { model.set(v = it) }, onRelease = { model.release() }
        )

        Spacer(Modifier.height(10.dp))
        SectionTitle("RGB")
        LabeledSlider(
            "Rot", "$r", r / 255f,
            Brush.horizontalGradient(listOf(Color(ColorMath.argb(0, g, b)), Color(ColorMath.argb(255, g, b)))),
            argb.toUiColor(),
            onChange = { model.setArgbLive(ColorMath.argb((it * 255).roundToInt(), g, b)) }, onRelease = { model.release() }
        )
        LabeledSlider(
            "Grün", "$g", g / 255f,
            Brush.horizontalGradient(listOf(Color(ColorMath.argb(r, 0, b)), Color(ColorMath.argb(r, 255, b)))),
            argb.toUiColor(),
            onChange = { model.setArgbLive(ColorMath.argb(r, (it * 255).roundToInt(), b)) }, onRelease = { model.release() }
        )
        LabeledSlider(
            "Blau", "$b", b / 255f,
            Brush.horizontalGradient(listOf(Color(ColorMath.argb(r, g, 0)), Color(ColorMath.argb(r, g, 255)))),
            argb.toUiColor(),
            onChange = { model.setArgbLive(ColorMath.argb(r, g, (it * 255).roundToInt())) }, onRelease = { model.release() }
        )

        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Hex", color = ui.textMuted, fontSize = 12.sp, modifier = Modifier.width(76.dp))
            HexField(argb) { model.pick(it) }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text, color = LocalUi.current.text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(bottom = 4.dp, top = 2.dp)
    )
}

/** Hex-Eingabe: übernimmt bei 6 gültigen Zeichen bzw. bei "Fertig"; ungültige Eingabe wird zurückgesetzt. */
@Composable
private fun HexField(color: Int, onCommit: (Int) -> Unit) {
    val ui = LocalUi.current
    val focus = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    var value by remember { mutableStateOf(TextFieldValue(ColorMath.toHex(color))) }

    // Externe Farbänderungen übernehmen, solange nicht getippt wird
    LaunchedEffect(color, focused) {
        if (!focused) value = TextFieldValue(ColorMath.toHex(color))
    }

    fun commit() {
        val c = ColorMath.parseHex(value.text)
        if (c != null) onCommit(c) else value = TextFieldValue(ColorMath.toHex(color))
    }

    Box(
        Modifier
            .background(ui.panelElevated, RoundedCornerShape(6.dp))
            .border(0.5.dp, ui.divider, RoundedCornerShape(6.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("#", color = ui.textMuted, fontSize = 15.sp, fontFamily = FontFamily.Monospace)
            BasicTextField(
                value = value,
                onValueChange = { nv ->
                    val filtered = nv.text.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }.take(6)
                    value = TextFieldValue(filtered, TextRange(filtered.length))
                    if (filtered.length == 6) ColorMath.parseHex(filtered)?.let(onCommit)
                },
                singleLine = true,
                textStyle = TextStyle(color = ui.text, fontSize = 15.sp, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(ui.accent),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { commit(); focus.clearFocus() }),
                modifier = Modifier
                    .width(90.dp)
                    .onFocusChanged {
                        if (focused && !it.isFocused) commit()
                        focused = it.isFocused
                    }
            )
        }
    }
}
