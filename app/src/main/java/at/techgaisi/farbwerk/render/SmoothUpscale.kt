package at.techgaisi.farbwerk.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import java.util.WeakHashMap
import kotlin.math.hypot

/**
 * Hochwertige Vergrößerung der Leinwand beim Hineinzoomen.
 *
 * Bilinear (Standard) wirkt bei starkem Zoom verwaschen, "Nächster Nachbar" zeigt harte Pixelblöcke.
 * Ab 150 % Zoom wird jede Ebene stattdessen bikubisch (Catmull-Rom, 16 Abtastpunkte) auf der GPU
 * vergrößert: Kanten und Rundungen bleiben glatt und deutlich schärfer als bilinear.
 * Verfügbar ab Android 13 (RuntimeShader); darunter bleibt es bei bilinear.
 */
object SmoothUpscale {
    /** Ab diesem Zoom wird bikubisch vergrößert. */
    private const val MIN_ZOOM = 1.5f

    private const val AGSL = """
        uniform shader image;
        uniform float2 size;

        // Catmull-Rom-Gewichte für den Abstand t (0..1) zum linken Nachbarn
        float4 weights(float t) {
            float t2 = t * t;
            float t3 = t2 * t;
            return float4(
                -0.5 * t3 + t2 - 0.5 * t,
                 1.5 * t3 - 2.5 * t2 + 1.0,
                -1.5 * t3 + 2.0 * t2 + 0.5 * t,
                 0.5 * t3 - 0.5 * t2
            );
        }

        half4 px(float2 p) {
            p = clamp(p, float2(0.5), size - float2(0.5));
            return image.eval(p);
        }

        half4 main(float2 coord) {
            float2 st = coord - 0.5;
            float2 f = fract(st);
            float2 base = floor(st) + 0.5;
            float4 wx = weights(f.x);
            float4 wy = weights(f.y);
            half4 acc = half4(0);
            for (int j = 0; j < 4; j++) {
                half4 row = half4(0);
                for (int i = 0; i < 4; i++) {
                    row += px(base + float2(float(i) - 1.0, float(j) - 1.0)) * half(wx[i]);
                }
                acc += row * half(wy[j]);
            }
            // Überschwinger begrenzen (vormultipliziert: Farbe ≤ Alpha)
            acc.a = clamp(acc.a, 0.0, 1.0);
            acc.rgb = clamp(acc.rgb, half3(0), half3(acc.a));
            return acc;
        }
    """

    private class Entry(val shader: RuntimeShader, var w: Int, var h: Int)
    private val cache = WeakHashMap<Bitmap, Entry>()
    private val tmpMatrix = Matrix()
    private val tmpValues = FloatArray(9)
    @Volatile private var failed = false

    /** Soll bei diesem Canvas-Zoom bikubisch vergrößert werden? */
    fun wanted(canvas: Canvas): Boolean {
        if (failed || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        @Suppress("DEPRECATION")
        canvas.getMatrix(tmpMatrix)
        tmpMatrix.getValues(tmpValues)
        val scale = hypot(tmpValues[Matrix.MSCALE_X], tmpValues[Matrix.MSKEW_Y])
        return scale >= MIN_ZOOM
    }

    /**
     * Zeichnet [bitmap] an (0,0) mit bikubischer Vergrößerung. Liefert false, wenn das nicht möglich
     * war – dann soll der Aufrufer normal zeichnen. [paint] liefert Deckkraft und Mischmodus.
     */
    fun draw(canvas: Canvas, bitmap: Bitmap, paint: Paint): Boolean {
        if (failed || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return try {
            val e = synchronized(cache) {
                cache[bitmap] ?: Entry(RuntimeShader(AGSL), 0, 0).also { cache[bitmap] = it }
            }
            if (e.w != bitmap.width || e.h != bitmap.height) {
                e.shader.setInputShader("image", BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP))
                e.shader.setFloatUniform("size", bitmap.width.toFloat(), bitmap.height.toFloat())
                e.w = bitmap.width; e.h = bitmap.height
            }
            val oldShader = paint.shader
            paint.shader = e.shader
            canvas.drawRect(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat(), paint)
            paint.shader = oldShader
            true
        } catch (t: Throwable) {
            android.util.Log.w("Farbwerk", "Bikubische Vergrößerung nicht verfügbar", t)
            failed = true
            false
        }
    }
}
