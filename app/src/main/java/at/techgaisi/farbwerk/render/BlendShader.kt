package at.techgaisi.farbwerk.render

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.RenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import androidx.annotation.RequiresApi
import at.techgaisi.farbwerk.model.LayerBlend

/**
 * GPU-Pfad (ab Android 13) für die Mischmodi aus [BlendMath]: ein AGSL-[RuntimeShader], der als
 * [RenderEffect] auf einen RenderNode mit allem Darunterliegenden („backdrop“) gelegt wird und die
 * Ebene ([src]) samt Maske und Schnittmaske darauf verrechnet. Formeln identisch mit [BlendMath].
 *
 * Eine Instanz je zu mischender Ebene im Bild; der Shader wird einmal kompiliert und wiederverwendet,
 * pro Bild werden nur Eingaben/Uniforms gesetzt und ein neuer RenderEffect (Momentaufnahme) erzeugt.
 */
@RequiresApi(33)
internal class BlendShader {
    private val shader = RuntimeShader(AGSL)

    /**
     * Baut den Effekt für eine Ebene.
     * @param src Ebeneninhalt (vormultipliziert)
     * @param mask Ebenenmaske oder null; [maskIsAlpha] = ALPHA_8 (sonst Luminanz einer ARGB-Maske)
     * @param clip Inhalt der Schnittmasken-Basis (nur Alpha zählt) oder null
     */
    fun effect(
        src: Bitmap, mask: Bitmap?, maskIsAlpha: Boolean, clip: Bitmap?,
        mode: LayerBlend, opacity: Float, filter: Boolean,
    ): RenderEffect {
        shader.setInputShader("src", bitmapShader(src, filter))
        shader.setInputShader("maskTex", bitmapShader(mask ?: dummy, filter))
        shader.setInputShader("clipTex", bitmapShader(clip ?: dummy, filter))
        shader.setFloatUniform("mode", BlendMath.code(mode).toFloat())
        shader.setFloatUniform("opacity", opacity)
        shader.setFloatUniform("maskKind", if (mask == null) 0f else if (maskIsAlpha) 1f else 2f)
        shader.setFloatUniform("useClip", if (clip == null) 0f else 1f)
        shader.setFloatUniform("hardMixThreshold", BlendMath.HARD_MIX_THRESHOLD)
        return RenderEffect.createRuntimeShaderEffect(shader, "backdrop")
    }

    private fun bitmapShader(b: Bitmap, filter: Boolean): BitmapShader =
        BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            filterMode = if (filter) BitmapShader.FILTER_MODE_LINEAR else BitmapShader.FILTER_MODE_NEAREST
        }

    companion object {
        /** Platzhalter für nicht benutzte Eingaben. */
        private val dummy: Bitmap by lazy { Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }

        private const val AGSL = """
uniform shader backdrop;
uniform shader src;
uniform shader maskTex;
uniform shader clipTex;
uniform float mode;
uniform float opacity;
uniform float maskKind;
uniform float useClip;
uniform float hardMixThreshold;

float burnC(float b, float s) {
    if (b >= 1.0) return 1.0;
    if (s <= 0.0) return 0.0;
    return max(0.0, 1.0 - (1.0 - b) / s);
}

float dodgeC(float b, float s) {
    if (b <= 0.0) return 0.0;
    if (s >= 1.0) return 1.0;
    return min(1.0, b / (1.0 - s));
}

float channel(float b, float s, int m) {
    if (m == 0) return max(0.0, b + s - 1.0);                               // Linear nachbelichten
    if (m == 4) return s < 0.5 ? burnC(b, 2.0 * s) : dodgeC(b, 2.0 * (s - 0.5)); // Strahlendes Licht
    if (m == 5) return clamp(b + 2.0 * s - 1.0, 0.0, 1.0);                  // Lineares Licht
    if (m == 6) return s < 0.5 ? min(b, 2.0 * s) : max(b, 2.0 * s - 1.0);   // Lichtpunkt
    if (m == 7) return (b + s >= hardMixThreshold) ? 1.0 : 0.0;             // Harte Mischung
    if (m == 8) return max(0.0, b - s);                                     // Subtrahieren
    if (m == 9) {                                                            // Dividieren
        if (s <= 0.0) return b <= 0.0 ? 0.0 : 1.0;
        return min(1.0, b / s);
    }
    return s;
}

float lum(float3 c) { return dot(c, float3(0.3, 0.59, 0.11)); }

half4 main(float2 p) {
    float4 B = float4(backdrop.eval(p));
    float4 S = float4(src.eval(p));
    float a = opacity;
    if (maskKind > 1.5) {
        float4 m = float4(maskTex.eval(p));
        // ARGB-Maske: Helligkeit (Rec. 709) wie der native Pfad im Compositor
        a *= m.a > 0.0 ? clamp(dot(m.rgb / m.a, float3(0.2126, 0.7152, 0.0722)), 0.0, 1.0) : 0.0;
    } else if (maskKind > 0.5) {
        a *= float(maskTex.eval(p).a);
    }
    if (useClip > 0.5) a *= float(clipTex.eval(p).a);
    float aS = S.a * a;
    if (aS <= 0.0) return half4(B);
    float3 cs = S.rgb / S.a;
    float aB = B.a;
    float3 cb = aB > 0.0 ? B.rgb / aB : float3(0.0);
    int m = int(mode + 0.5);
    float aO = aS + aB * (1.0 - aS);
    float3 co;
    if (m == 1) {
        // Schattieren: Ebene auf Weiß, dann Abdunkeln
        co = aS * (1.0 - aB) * cs + aB * min(cb, float3(1.0 - aS) + aS * cs);
    } else {
        float3 mixed;
        if (m == 2 || m == 3) {
            float ls = lum(cs);
            float lb = lum(cb);
            bool takeSrc = (m == 2) ? (ls < lb) : (ls > lb);
            mixed = takeSrc ? cs : cb;
        } else {
            mixed = float3(channel(cb.r, cs.r, m), channel(cb.g, cs.g, m), channel(cb.b, cs.b, m));
        }
        co = aS * (1.0 - aB) * cs + aS * aB * mixed + (1.0 - aS) * aB * cb;
    }
    return half4(co, aO);
}
"""
    }
}
