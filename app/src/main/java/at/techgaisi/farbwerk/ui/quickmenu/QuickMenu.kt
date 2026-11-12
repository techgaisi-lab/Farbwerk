package at.techgaisi.farbwerk.ui.quickmenu

import android.graphics.PointF
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import at.techgaisi.farbwerk.EditorState
import at.techgaisi.farbwerk.canvas.QuickMenuHook
import at.techgaisi.farbwerk.ui.actions.FwDialog
import at.techgaisi.farbwerk.ui.actions.FwTextField
import at.techgaisi.farbwerk.ui.common.PanelCard
import at.techgaisi.farbwerk.ui.icons.AppIcons
import at.techgaisi.farbwerk.ui.theme.LocalUi
import at.techgaisi.farbwerk.ui.theme.UiMetrics
import at.techgaisi.farbwerk.ui.theme.UiMotion
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Steuerung des QuickMenus (Anleitung §5). Position = [EditorState.quickMenuAt] (Fenster-Koordinaten).
 *
 *  - [openAt] mit dragging = true: Schnellwahl – Finger liegt noch auf der Leinwand, die Richtung zum Knopf
 *    hebt ihn hervor, Loslassen löst aus (ohne Wahl bleibt das Menü offen).
 *  - Ohne Ziehen bleibt das Menü offen, bis ein Knopf getippt, weggetippt oder weitergemalt wird.
 */
object QuickMenuController {
    /** Hervorgehobener Knopf (0..5) beim Ziehen, sonst −1. */
    var hovered by mutableIntStateOf(-1)
        private set
    /** Finger liegt noch (Schnellwahl). */
    var dragging by mutableStateOf(false)
        private set
    /** Fenster-Offset des Menü-Overlays (für die Umrechnung). */
    internal var radiusPx = 1f
    internal var centerRadiusPx = 1f

    /** Knopf-Zuweisung läuft (Halten auf einem Knopf) – Index oder −1. */
    var assigning by mutableIntStateOf(-1)
    /** Profilliste offen. */
    var profilesOpen by mutableStateOf(false)

    private var state: EditorState? = null

    fun bind(s: EditorState): QuickMenuHook {
        state = s
        return object : QuickMenuHook {
            override fun openAt(windowX: Float, windowY: Float, dragging: Boolean) = open(s, windowX, windowY, dragging)
            override fun dragTo(windowX: Float, windowY: Float) = QuickMenuController.dragTo(s, windowX, windowY)
            override fun release(windowX: Float, windowY: Float) = QuickMenuController.release(s)
            override fun dismiss() = close(s)
        }
    }

    fun open(s: EditorState, windowX: Float, windowY: Float, dragging: Boolean) {
        s.openPanel = at.techgaisi.farbwerk.model.Panel.NONE
        assigning = -1
        profilesOpen = false
        hovered = -1
        this.dragging = dragging
        s.quickMenuAt = PointF(windowX, windowY)
        s.canvasView?.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
    }

    /** Öffnet das Menü an der zuletzt bemalten Stelle (bzw. Bildschirmmitte), z. B. per Leertaste/Modifizierer. */
    fun openAtLastPoint(s: EditorState) {
        val cv = s.canvasView ?: return
        val p = s.lastPaintPoint
        val w = if (p != null) cv.docToWindow(p.x, p.y) else {
            val loc = IntArray(2); cv.getLocationInWindow(loc)
            PointF(loc[0] + cv.width / 2f, loc[1] + cv.height / 2f)
        }
        open(s, w.x, w.y, dragging = false)
    }

    fun toggle(s: EditorState) { if (s.quickMenuAt != null) close(s) else openAtLastPoint(s) }

    internal fun dragTo(s: EditorState, windowX: Float, windowY: Float) {
        val c = s.quickMenuAt ?: return
        val dx = windowX - c.x; val dy = windowY - c.y
        val h = if (hypot(dx, dy) < centerRadiusPx) -1 else slotForAngle(atan2(dy, dx))
        if (h != hovered) {
            hovered = h
            if (h >= 0) s.canvasView?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
    }

    internal fun release(s: EditorState) {
        dragging = false
        val h = hovered
        hovered = -1
        if (h >= 0) trigger(s, h)
    }

    fun close(s: EditorState) {
        s.quickMenuAt = null
        hovered = -1
        dragging = false
        assigning = -1
        profilesOpen = false
    }

    /** Knopf [slot] auslösen: Menü schließen, dann Aktion an der Menüposition ausführen. */
    fun trigger(s: EditorState, slot: Int) {
        val c = s.quickMenuAt
        val action = QuickMenuStore.active(s).actions.getOrNull(slot) ?: return
        val doc = c?.let { s.windowToDoc(it.x, it.y) }
        close(s)
        if (action == QuickMenuAction.NONE) return
        try { action.run(s, doc?.x, doc?.y) } catch (t: Throwable) {
            android.util.Log.e("Farbwerk", "QuickMenu", t)
            s.flash("Aktion nicht möglich")
        }
        s.invalidate()
    }

    /** Winkel (Radiant, 0 = rechts, im Uhrzeigersinn) → Knopf 0..5 (0 oben, im Uhrzeigersinn). */
    private fun slotForAngle(a: Float): Int {
        var deg = Math.toDegrees(a.toDouble()) + 90.0   // 0° = oben
        while (deg < 0) deg += 360.0
        return (((deg + 30.0) / 60.0).toInt()) % 6
    }

    /** Mittelpunkt von Knopf [slot] relativ zur Menümitte. */
    internal fun slotOffset(slot: Int, radius: Float): Offset {
        val a = -PI / 2 + slot * PI / 3
        return Offset((cos(a) * radius).toFloat(), (sin(a) * radius).toFloat())
    }
}

/**
 * Darstellung des QuickMenus: Ring aus 6 runden Knöpfen (je 64 dp) um einen Mittelknopf (56 dp, Profilname),
 * Durchmesser ~248 dp. Ploppt gefedert auf. Halten auf einem Knopf → „Aktion festlegen“;
 * Mittelknopf → Profile (wechseln, „+“ neu, Doppeltippen benennt um).
 */
@Composable
fun QuickMenuOverlay(state: EditorState) {
    val at = state.quickMenuAt ?: return
    val ui = LocalUi.current
    val density = LocalDensity.current
    val view = LocalView.current
    val profile = QuickMenuStore.active(state).also { @Suppress("UNUSED_VARIABLE") val p = state.prefs.quickMenuProfiles }
    var origin by remember { mutableStateOf(Offset.Zero) }
    val appear = remember(at) { Animatable(0f) }
    LaunchedEffect(at) { appear.animateTo(1f, UiMotion.pop()) }

    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow() }) {
        val w = constraints.maxWidth.toFloat(); val h = constraints.maxHeight.toFloat()
        with(density) {
            val diameter = UiMetrics.quickMenuDiameter.toPx()
            val btn = UiMetrics.quickMenuButton.toPx()
            val center = UiMetrics.quickMenuCenter.toPx()
            val radius = (diameter - btn) / 2
            QuickMenuController.radiusPx = radius
            QuickMenuController.centerRadiusPx = center / 2 + 6.dp.toPx()
            val margin = 8.dp.toPx()
            val cx = (at.x - origin.x).coerceIn(diameter / 2 + margin, w - diameter / 2 - margin)
            val cy = (at.y - origin.y).coerceIn(diameter / 2 + margin, h - diameter / 2 - margin)
            // Liegt die Mitte nach dem Einpassen woanders, folgt die Schnellwahl der sichtbaren Mitte
            if (cx != at.x - origin.x || cy != at.y - origin.y) {
                LaunchedEffect(cx, cy) { state.quickMenuAt = PointF(cx + origin.x, cy + origin.y) }
            }

            Box(
                Modifier
                    .offset { IntOffset((cx - diameter / 2).roundToInt(), (cy - diameter / 2).roundToInt()) }
                    .size(UiMetrics.quickMenuDiameter)
                    .graphicsLayer {
                        val p = appear.value
                        val s = 0.6f + 0.4f * p
                        scaleX = s; scaleY = s; alpha = p.coerceIn(0f, 1f)
                    }
            ) {
                for (slot in 0 until 6) {
                    val o = QuickMenuController.slotOffset(slot, radius)
                    val action = profile.actions.getOrElse(slot) { QuickMenuAction.NONE }
                    QuickMenuButton(
                        label = action.label,
                        highlighted = QuickMenuController.hovered == slot,
                        modifier = Modifier.offset {
                            IntOffset((diameter / 2 + o.x - btn / 2).roundToInt(), (diameter / 2 + o.y - btn / 2).roundToInt())
                        },
                        onTap = { QuickMenuController.trigger(state, slot) },
                        onHold = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            QuickMenuController.profilesOpen = false
                            QuickMenuController.assigning = slot
                        },
                    )
                }
                // Mittelknopf mit Profilname
                Box(
                    Modifier.align(Alignment.Center).size(UiMetrics.quickMenuCenter)
                        .shadow(10.dp, CircleShape, ambientColor = ui.shadow, spotColor = ui.shadow)
                        .clip(CircleShape).background(ui.panelElevated)
                        .clickable {
                            QuickMenuController.assigning = -1
                            QuickMenuController.profilesOpen = !QuickMenuController.profilesOpen
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(profile.name, color = ui.text, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                        lineHeight = 12.sp, modifier = Modifier.padding(4.dp))
                }
            }

            // Zusatzkarten (Aktion festlegen / Profile) neben dem Menü
            val cardW = 260.dp.toPx()
            val leftSide = cx + diameter / 2 + cardW + margin > w
            val cardX = if (leftSide) cx - diameter / 2 - cardW - 4.dp.toPx() else cx + diameter / 2 + 4.dp.toPx()
            val cardY = (cy - diameter / 2).coerceIn(margin, (h - 360.dp.toPx()).coerceAtLeast(margin))
            val cardMod = Modifier
                .offset { IntOffset(cardX.coerceAtLeast(margin).roundToInt(), cardY.roundToInt()) }
                .width(260.dp).heightIn(max = 420.dp)
            if (QuickMenuController.assigning >= 0) AssignCard(state, QuickMenuController.assigning, cardMod)
            else if (QuickMenuController.profilesOpen) ProfilesCard(state, cardMod)
        }
    }
}

@Composable
private fun QuickMenuButton(
    label: String,
    highlighted: Boolean,
    modifier: Modifier,
    onTap: () -> Unit,
    onHold: () -> Unit,
) {
    val ui = LocalUi.current
    val bg by animateColorAsState(if (highlighted) ui.accent else ui.panel, UiMotion.quick(), label = "qmBg")
    val scale by animateFloatAsState(if (highlighted) 1.08f else 1f, UiMotion.pop(), label = "qmScale")
    Box(
        modifier
            .size(UiMetrics.quickMenuButton)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(12.dp, CircleShape, ambientColor = ui.shadow, spotColor = ui.shadow)
            .clip(CircleShape)
            .background(bg)
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }, onLongPress = { onHold() }) },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label, color = if (highlighted) ui.onAccent else ui.text, fontSize = 10.5.sp, lineHeight = 12.sp,
            textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 5.dp),
        )
    }
}

/** „Aktion festlegen“: gruppierte Liste aller Aktionen für einen Knopf. */
@Composable
private fun AssignCard(state: EditorState, slot: Int, modifier: Modifier) {
    val ui = LocalUi.current
    val current = QuickMenuStore.active(state).actions.getOrNull(slot)
    PanelCard(modifier, title = "Aktion festlegen") {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 8.dp)) {
            for (g in QuickMenuGroup.entries) {
                Text(g.label.uppercase(), color = ui.textMuted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 4.dp))
                for (a in QuickMenuAction.entries.filter { it.group == g }) {
                    val sel = a == current
                    Row(
                        Modifier.fillMaxWidth().clickable {
                            QuickMenuStore.assign(state, slot, a)
                            QuickMenuController.assigning = -1
                        }.padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(a.label, color = if (sel) ui.accent else ui.text, fontSize = UiMetrics.listSize,
                            fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** Profilliste: Tippen wechselt, „+“ legt an, Doppeltippen benennt um, Halten löscht (nicht das letzte). */
@Composable
private fun ProfilesCard(state: EditorState, modifier: Modifier) {
    val ui = LocalUi.current
    val list = QuickMenuStore.profiles(state)
    @Suppress("UNUSED_VARIABLE") val raw = state.prefs.quickMenuProfiles
    val active = state.prefs.quickMenuProfile.coerceIn(0, list.size - 1)
    var renaming by remember { mutableIntStateOf(-1) }
    var confirmDelete by remember { mutableIntStateOf(-1) }
    PanelCard(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 6.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("QuickMenu-Profile", color = ui.text, fontSize = UiMetrics.titleSize, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f))
            Box(Modifier.size(40.dp).clip(RoundedCornerShape(8.dp)).clickable { QuickMenuStore.addProfile(state) },
                contentAlignment = Alignment.Center) {
                Icon(AppIcons.Plus, "Neues Profil", tint = ui.accent, modifier = Modifier.size(22.dp))
            }
        }
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)) {
            list.forEachIndexed { i, p ->
                val sel = i == active
                Text(
                    p.name, color = if (sel) ui.onAccent else ui.text, fontSize = UiMetrics.listSize,
                    fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 6.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (sel) ui.selectedRow else androidx.compose.ui.graphics.Color.Transparent)
                        .pointerInput(i) {
                            detectTapGestures(
                                onTap = { QuickMenuStore.select(state, i) },
                                onDoubleTap = { renaming = i },
                                onLongPress = { if (list.size > 1) confirmDelete = i },
                            )
                        }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                )
            }
        }
        Text("Doppeltippen benennt um · Halten löscht", color = ui.textMuted, fontSize = 11.sp,
            modifier = Modifier.padding(start = 16.dp, bottom = 10.dp))
        Spacer(Modifier.size(0.dp))
    }
    if (renaming >= 0) {
        var name by remember(renaming) { mutableStateOf(list.getOrNull(renaming)?.name ?: "") }
        FwDialog("Profil umbenennen", onDismiss = { renaming = -1 }, confirmEnabled = name.isNotBlank(),
            onConfirm = { QuickMenuStore.rename(state, renaming, name); renaming = -1 }) {
            FwTextField(name, { name = it }, "Name", Modifier.fillMaxWidth().padding(horizontal = 20.dp))
        }
    }
    if (confirmDelete >= 0) {
        FwDialog("Profil „${list.getOrNull(confirmDelete)?.name}“ löschen?", onDismiss = { confirmDelete = -1 },
            confirmLabel = "Löschen", destructive = true,
            onConfirm = { QuickMenuStore.delete(state, confirmDelete); confirmDelete = -1 }) {}
    }
}
