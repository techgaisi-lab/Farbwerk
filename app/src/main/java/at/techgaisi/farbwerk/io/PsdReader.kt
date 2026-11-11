package at.techgaisi.farbwerk.io

import android.graphics.Bitmap
import android.graphics.Color
import at.techgaisi.farbwerk.model.ArtDocument
import at.techgaisi.farbwerk.model.Layer
import at.techgaisi.farbwerk.model.LayerBlend
import at.techgaisi.farbwerk.render.LayerGroup
import at.techgaisi.farbwerk.render.LayerGroups
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.util.UUID
import java.util.zip.Inflater

/**
 * Liest Photoshop-Dateien (PSD Version 1, 8 Bit, RGB oder Graustufen).
 * Ebenen werden mit Name, Deckkraft, Sichtbarkeit, Mischmodus, Schnittmaske, Alpha-Sperre,
 * Maske und Gruppenzugehörigkeit übernommen. Enthält die Datei keine Ebenen, wird das
 * zusammengefügte Bild als einzelne Ebene importiert.
 */
object PsdReader {
    private const val MAX_PIXELS = 40_000_000L

    private class Rec {
        var top = 0; var left = 0; var bottom = 0; var right = 0
        val chIds = ArrayList<Int>()
        val chLens = ArrayList<Long>()
        var blend = "norm"
        var opacity = 255
        var clipping = 0
        var flags = 0
        var name = ""
        var section = 0
        var protect = 0
        var maskTop = 0; var maskLeft = 0; var maskBottom = 0; var maskRight = 0
        var maskDefault = 0
        var hasMask = false
        // Ergebnis
        var bitmap: Bitmap? = null
        var mask: Bitmap? = null
    }

    fun read(input: InputStream, name: String): ArtDocument {
        val ins = DataInputStream(input.buffered(1 shl 16))
        if (ins.readInt() != 0x38425053) throw IOException("Keine PSD-Datei")
        val version = ins.readUnsignedShort()
        if (version != 1) throw IOException("PSB (große Dokumente) wird nicht unterstützt")
        ins.skipFully(6)
        val channels = ins.readUnsignedShort()
        val h = ins.readInt(); val w = ins.readInt()
        val depth = ins.readUnsignedShort()
        val mode = ins.readUnsignedShort()
        if (depth != 8) throw IOException("Nur 8 Bit pro Kanal werden unterstützt")
        if (mode != 3 && mode != 1) throw IOException("Nur RGB- und Graustufen-PSD werden unterstützt")
        if (w <= 0 || h <= 0 || w.toLong() * h > MAX_PIXELS) throw IOException("Bild ist zu groß")
        val gray = mode == 1

        ins.skipFully(ins.readInt().toLong())   // Farbmodusdaten
        // Bildressourcen (Auflösung lesen)
        var dpi = 72
        val resLen = ins.readInt()
        val res = ByteArray(resLen); ins.readFully(res)
        parseResolution(res)?.let { dpi = it }

        val doc = ArtDocument(name, w, h)
        doc.dpi = dpi.coerceIn(1, 4800)

        // ---------------- Ebenen ----------------
        val lmLen = ins.readInt().toLong() and 0xFFFFFFFFL
        val recs = ArrayList<Rec>()
        if (lmLen > 0) {
            val lmData = ByteArray(lmLen.toInt()); ins.readFully(lmData)
            try {
                readLayers(lmData, w, h, gray, recs)
            } catch (t: Throwable) {
                android.util.Log.w("FarbwerkPSD", "Ebenen nicht lesbar, verwende Composite", t)
                recs.forEach { it.bitmap?.recycle(); it.mask?.recycle() }
                recs.clear()
            }
        }

        if (recs.any { it.section == 0 && it.bitmap != null }) {
            buildLayers(doc, recs)
        } else {
            // ---------------- Zusammengefügtes Bild ----------------
            val bmp = readComposite(ins, w, h, channels, gray)
            doc.layers.add(Layer("Ebene 1", bmp))
        }
        if (doc.layers.isEmpty()) doc.layers.add(Layer("Ebene 1", doc.newLayerBitmap()))
        doc.activeIndex = doc.layers.lastIndex
        doc.dirty = true
        return doc
    }

    private fun parseResolution(res: ByteArray): Int? {
        val b = ByteBuffer.wrap(res)
        while (b.remaining() >= 12) {
            if (b.int != 0x3842494D) return null
            val id = b.short.toInt() and 0xFFFF
            val nlen = b.get().toInt() and 0xFF
            var skip = nlen; if ((nlen + 1) % 2 != 0) skip++
            b.position(b.position() + skip)
            val size = b.int
            if (id == 0x03ED && size >= 16) return (b.int ushr 16).takeIf { it > 0 }
            b.position(b.position() + size + (size and 1))
        }
        return null
    }

    private fun readLayers(data: ByteArray, w: Int, h: Int, gray: Boolean, recs: MutableList<Rec>) {
        val b = ByteBuffer.wrap(data)
        val liLen = b.int.toLong() and 0xFFFFFFFFL
        if (liLen == 0L) return
        val count = Math.abs(b.short.toInt())
        repeat(count) {
            val r = Rec()
            r.top = b.int; r.left = b.int; r.bottom = b.int; r.right = b.int
            val nch = b.short.toInt() and 0xFFFF
            repeat(nch) { r.chIds += b.short.toInt(); r.chLens += b.int.toLong() and 0xFFFFFFFFL }
            if (b.int != 0x3842494D) throw IOException("Ungültige Ebenensignatur")
            val key = ByteArray(4); b.get(key); r.blend = String(key, Charsets.ISO_8859_1)
            r.opacity = b.get().toInt() and 0xFF
            r.clipping = b.get().toInt() and 0xFF
            r.flags = b.get().toInt() and 0xFF
            b.get()
            val extraLen = b.int
            val extraEnd = b.position() + extraLen
            // Maske
            val mLen = b.int
            if (mLen >= 20) {
                r.maskTop = b.int; r.maskLeft = b.int; r.maskBottom = b.int; r.maskRight = b.int
                r.maskDefault = b.get().toInt() and 0xFF
                r.hasMask = true
                b.position(b.position() + mLen - 17)
            } else b.position(b.position() + mLen)
            // Mischbereiche
            b.position(b.position() + b.int)
            // Pascal-Name (auf 4 Bytes aufgefüllt)
            val nl = b.get().toInt() and 0xFF
            val nb = ByteArray(nl); b.get(nb)
            r.name = String(nb, Charsets.ISO_8859_1)
            val total = 1 + nl
            b.position(b.position() + (4 - total % 4) % 4)
            // Zusatzinfos
            while (b.position() + 12 <= extraEnd) {
                val sig = b.int
                if (sig != 0x3842494D && sig != 0x38423634) break
                val k = ByteArray(4); b.get(k)
                val ks = String(k, Charsets.ISO_8859_1)
                val len = b.int
                val start = b.position()
                when (ks) {
                    "luni" -> {
                        val n = b.int
                        if (n in 0..1000) {
                            val sb = StringBuilder(); repeat(n) { sb.append(b.char) }
                            r.name = sb.toString().trimEnd('\u0000')
                        }
                    }
                    "lsct", "lsdk" -> r.section = b.int
                    "lspf" -> r.protect = b.int
                }
                b.position(minOf(extraEnd, start + len))
            }
            b.position(extraEnd)
            recs += r
        }
        // Kanaldaten
        for (r in recs) decodeChannels(b, r, w, h, gray)
    }

    private fun decodeChannels(b: ByteBuffer, r: Rec, docW: Int, docH: Int, gray: Boolean) {
        val lw = r.right - r.left; val lh = r.bottom - r.top
        var a: ByteArray? = null; var cr: ByteArray? = null; var cg: ByteArray? = null; var cb: ByteArray? = null
        var m: ByteArray? = null
        val mw = r.maskRight - r.maskLeft; val mh = r.maskBottom - r.maskTop
        for (k in r.chIds.indices) {
            val id = r.chIds[k]
            val len = r.chLens[k].toInt()
            val start = b.position()
            val end = start + len
            if (len >= 2) {
                val isMask = id == -2
                val cw = if (isMask) mw else lw
                val ch = if (isMask) mh else lh
                if (cw > 0 && ch > 0 && (id in -2..2)) {
                    val out = ByteArray(cw * ch)
                    decodeChannel(b, end, cw, ch, out)
                    when (id) { -1 -> a = out; 0 -> cr = out; 1 -> cg = out; 2 -> cb = out; -2 -> m = out }
                }
            }
            b.position(end)
        }
        if (r.section != 0) return
        val bmp = Bitmap.createBitmap(docW, docH, Bitmap.Config.ARGB_8888)
        if (lw > 0 && lh > 0) {
            // auf Dokument zuschneiden
            val x0 = maxOf(0, r.left); val y0 = maxOf(0, r.top)
            val x1 = minOf(docW, r.right); val y1 = minOf(docH, r.bottom)
            if (x1 > x0 && y1 > y0) {
                val row = IntArray(x1 - x0)
                for (y in y0 until y1) {
                    val base = (y - r.top) * lw
                    for (x in x0 until x1) {
                        val i = base + (x - r.left)
                        val rv = cr?.get(i)?.toInt()?.and(0xFF) ?: 0
                        val gv = if (gray) rv else cg?.get(i)?.toInt()?.and(0xFF) ?: 0
                        val bv = if (gray) rv else cb?.get(i)?.toInt()?.and(0xFF) ?: 0
                        val av = a?.get(i)?.toInt()?.and(0xFF) ?: 255
                        row[x - x0] = (av shl 24) or (rv shl 16) or (gv shl 8) or bv
                    }
                    bmp.setPixels(row, 0, x1 - x0, x0, y, x1 - x0, 1)
                }
            }
        }
        r.bitmap = bmp
        val mm = m
        if (r.hasMask && mm != null) {
            val mask = Bitmap.createBitmap(docW, docH, Bitmap.Config.ALPHA_8)
            val rb = mask.rowBytes
            val buf = ByteArray(rb * docH)
            java.util.Arrays.fill(buf, r.maskDefault.toByte())
            for (y in maxOf(0, r.maskTop) until minOf(docH, r.maskBottom)) {
                for (x in maxOf(0, r.maskLeft) until minOf(docW, r.maskRight)) {
                    buf[y * rb + x] = mm[(y - r.maskTop) * mw + (x - r.maskLeft)]
                }
            }
            mask.copyPixelsFromBuffer(ByteBuffer.wrap(buf))
            r.mask = mask
        }
    }

    /** Dekodiert einen Kanal (Kompression 0 roh, 1 RLE, 2 ZIP, 3 ZIP mit Prädiktion). */
    private fun decodeChannel(b: ByteBuffer, end: Int, w: Int, h: Int, out: ByteArray) {
        val comp = b.short.toInt()
        when (comp) {
            0 -> b.get(out, 0, minOf(out.size, end - b.position()))
            1 -> {
                val counts = IntArray(h) { b.short.toInt() and 0xFFFF }
                val arr = b.array()
                var p = b.position()
                for (y in 0 until h) {
                    val rowEnd = minOf(end, p + counts[y])
                    PackBits.decode(arr, p, rowEnd, out, y * w, w)
                    p = rowEnd
                }
                b.position(p)
            }
            2, 3 -> {
                val inf = Inflater()
                inf.setInput(b.array(), b.position(), end - b.position())
                var o = 0
                while (o < out.size && !inf.finished()) {
                    val n = inf.inflate(out, o, out.size - o)
                    if (n == 0 && (inf.needsInput() || inf.needsDictionary())) break
                    o += n
                }
                inf.end()
                if (comp == 3) for (y in 0 until h) {
                    val base = y * w
                    for (x in 1 until w) out[base + x] = (out[base + x] + out[base + x - 1]).toByte()
                }
            }
            else -> throw IOException("Unbekannte Kompression $comp")
        }
    }

    private fun buildLayers(doc: ArtDocument, recs: List<Rec>) {
        // Gruppen: Datei geht von unten nach oben; Gruppenende (3) öffnet, Ordner (1/2) schließt.
        val groupStack = ArrayList<String>()
        var first = true
        for (r in recs) {
            when (r.section) {
                3 -> { groupStack += UUID.randomUUID().toString(); continue }
                1, 2 -> {
                    if (groupStack.isNotEmpty()) {
                        val g = LayerGroup(groupStack.removeAt(groupStack.lastIndex), r.name.ifBlank { "Gruppe" })
                        g.visible = r.flags and 0x02 == 0
                        g.opacity = r.opacity / 255f
                        g.collapsed = r.section == 2
                        LayerGroups.put(doc, g)
                    }
                    continue
                }
            }
            val bmp = r.bitmap ?: continue
            if (first && r.name == PsdWriter.BACKGROUND_NAME) {
                // Eigene Hintergrundfarbe wiederherstellen
                first = false
                doc.backgroundColor = bmp.getPixel(bmp.width / 2, bmp.height / 2) or (0xFF shl 24)
                doc.backgroundVisible = r.flags and 0x02 == 0
                bmp.recycle()
                continue
            }
            first = false
            val l = Layer(r.name.ifBlank { "Ebene ${doc.layers.size + 1}" }, bmp)
            l.opacity = r.opacity / 255f
            l.visible = r.flags and 0x02 == 0
            l.alphaLock = r.flags and 0x01 != 0 || r.protect and 0x01 != 0
            l.locked = r.protect and 0x80000000.toInt() != 0
            l.clipping = r.clipping != 0
            l.blend = blendFromKey(r.blend)
            l.mask = r.mask
            l.groupId = groupStack.lastOrNull()
            doc.layers.add(l)
        }
    }

    fun blendFromKey(k: String): LayerBlend = when (k) {
        "mul " -> LayerBlend.MULTIPLY
        "dark", "dkCl" -> LayerBlend.DARKEN
        "idiv" -> LayerBlend.COLOR_BURN
        "lbrn" -> LayerBlend.LINEAR_BURN
        "scrn" -> LayerBlend.SCREEN
        "lite", "lgCl" -> LayerBlend.LIGHTEN
        "div " -> LayerBlend.COLOR_DODGE
        "lddg" -> LayerBlend.ADD
        "over" -> LayerBlend.OVERLAY
        "sLit" -> LayerBlend.SOFT_LIGHT
        "hLit", "vLit", "lLit", "pLit", "hMix" -> LayerBlend.HARD_LIGHT
        "diff" -> LayerBlend.DIFFERENCE
        "smud" -> LayerBlend.EXCLUSION
        "hue " -> LayerBlend.HUE
        "sat " -> LayerBlend.SATURATION
        "colr" -> LayerBlend.COLOR
        "lum " -> LayerBlend.LUMINOSITY
        else -> LayerBlend.NORMAL
    }

    private fun readComposite(ins: DataInputStream, w: Int, h: Int, channels: Int, gray: Boolean): Bitmap {
        val comp = ins.readUnsignedShort()
        val nch = minOf(channels, if (gray) 2 else 4)
        val planes = Array(nch) { ByteArray(w * h) }
        when (comp) {
            0 -> for (c in 0 until nch) ins.readFully(planes[c])
            1 -> {
                val counts = IntArray(channels * h) { ins.readUnsignedShort() }
                var buf = ByteArray(w * 2 + 16)
                for (c in 0 until nch) for (y in 0 until h) {
                    val n = counts[c * h + y]
                    if (buf.size < n) buf = ByteArray(n)
                    ins.readFully(buf, 0, n)
                    PackBits.decode(buf, 0, n, planes[c], y * w, w)
                }
            }
            else -> throw IOException("Komprimiertes Composite wird nicht unterstützt")
        }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val row = IntArray(w)
        val alphaPlane = if (gray) planes.getOrNull(1) else planes.getOrNull(3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val i = y * w + x
                val r = planes[0][i].toInt() and 0xFF
                val g = if (gray) r else planes[1][i].toInt() and 0xFF
                val bl = if (gray) r else planes[2][i].toInt() and 0xFF
                val a = alphaPlane?.get(i)?.toInt()?.and(0xFF) ?: 255
                row[x] = Color.argb(a, r, g, bl)
            }
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
        return bmp
    }

    private fun DataInputStream.skipFully(n: Long) {
        var left = n
        while (left > 0) {
            val s = skip(left)
            if (s <= 0) { if (read() < 0) throw EOFException(); left-- } else left -= s
        }
    }
}
