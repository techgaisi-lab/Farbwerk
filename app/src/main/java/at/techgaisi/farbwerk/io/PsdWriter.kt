package at.techgaisi.farbwerk.io

import android.graphics.Bitmap
import android.graphics.Color
import at.techgaisi.farbwerk.model.ArtDocument
import at.techgaisi.farbwerk.model.Layer
import at.techgaisi.farbwerk.model.LayerBlend
import at.techgaisi.farbwerk.render.LayerGroups
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * Schreibt echte Photoshop-Dateien (PSD, Version 1, RGB, 8 Bit) mit Ebenen:
 * Namen (ASCII + Unicode "luni"), Deckkraft, Sichtbarkeit, Mischmodus, Schnittmaske,
 * Alpha-Sperre/Sperre ("lspf"), Ebenenmasken, Gruppen ("lsct") und RLE-Kompression (PackBits).
 *
 * Kanaldaten werden zuerst in eine Temp-Datei geschrieben, damit auch große Dokumente ohne
 * riesige Puffer exportiert werden können.
 */
object PsdWriter {

    /** Abbildung der eigenen Mischmodi auf PSD-Schlüssel. */
    fun blendKey(b: LayerBlend): String = when (b) {
        LayerBlend.NORMAL -> "norm"
        LayerBlend.MULTIPLY -> "mul "
        LayerBlend.DARKEN -> "dark"
        LayerBlend.COLOR_BURN -> "idiv"
        LayerBlend.LINEAR_BURN -> "lbrn"
        LayerBlend.SCREEN -> "scrn"
        LayerBlend.LIGHTEN -> "lite"
        LayerBlend.COLOR_DODGE -> "div "
        LayerBlend.ADD -> "lddg"
        LayerBlend.OVERLAY -> "over"
        LayerBlend.SOFT_LIGHT -> "sLit"
        LayerBlend.HARD_LIGHT -> "hLit"
        LayerBlend.DIFFERENCE -> "diff"
        LayerBlend.EXCLUSION -> "smud"
        LayerBlend.HUE -> "hue "
        LayerBlend.SATURATION -> "sat "
        LayerBlend.COLOR -> "colr"
        LayerBlend.LUMINOSITY -> "lum "
        LayerBlend.SHADE -> "lbrn"
        LayerBlend.DARKER_COLOR -> "dkCl"
        LayerBlend.LIGHTER_COLOR -> "lgCl"
        LayerBlend.VIVID_LIGHT -> "vLit"
        LayerBlend.LINEAR_LIGHT -> "lLit"
        LayerBlend.PIN_LIGHT -> "pLit"
        LayerBlend.HARD_MIX -> "hMix"
        LayerBlend.SUBTRACT -> "fsub"
        LayerBlend.DIVIDE -> "fdiv"
    }

    /** Ein Eintrag der PSD-Ebenenliste (echte Ebene, Gruppenbeginn oder Gruppenende). */
    private class Rec(
        val layer: Layer?,
        val name: String,
        val sectionType: Int = 0,    // 0 = normale Ebene, 1 = offene Gruppe, 3 = Gruppenende
        val fillColor: Int? = null,  // für die Hintergrundfarbe-Ebene
        val visible: Boolean = true,
        val groupOpacity: Float = 1f,
    ) {
        var top = 0; var left = 0; var bottom = 0; var right = 0
        val channelIds = ArrayList<Int>()
        val channelLens = ArrayList<Long>()
        var hasMask = false
    }

    /**
     * @param composite zusammengefügtes Bild in Dokumentgröße (für Programme ohne Ebenenunterstützung)
     */
    fun write(doc: ArtDocument, composite: Bitmap, out: File, tmpDir: File) {
        val w = doc.width; val h = doc.height
        val recs = ArrayList<Rec>()
        // Hintergrundfarbe als eigene unterste Ebene (wird beim Import wieder erkannt)
        recs += Rec(null, BACKGROUND_NAME, fillColor = doc.backgroundColor or (0xFF shl 24), visible = doc.backgroundVisible)
        var i = 0
        var groupNo = 0
        // Private Ebenen werden nicht exportiert
        val layers = doc.layers.filter { !it.isPrivate }
        while (i < layers.size) {
            val g = layers[i].groupId
            if (g == null) { recs += Rec(layers[i], layers[i].name); i++; continue }
            groupNo++
            recs += Rec(null, "</Layer group>", sectionType = 3)
            while (i < layers.size && layers[i].groupId == g) { recs += Rec(layers[i], layers[i].name); i++ }
            val meta = LayerGroups.find(doc, g)
            recs += Rec(null, meta?.name ?: "Gruppe $groupNo", sectionType = 1, visible = meta?.visible ?: true,
                groupOpacity = meta?.opacity ?: 1f)
        }

        val chanFile = File.createTempFile("psd", ".bin", tmpDir)
        try {
            // ---------------- Pass 1: Kanaldaten kodieren ----------------
            DataOutputStream(BufferedOutputStream(FileOutputStream(chanFile), 1 shl 16)).use { cd ->
                val enc = ChannelEncoder(w)
                for (r in recs) encodeRecord(r, w, h, enc, cd)
            }

            // ---------------- Pass 2: Datei schreiben ----------------
            DataOutputStream(BufferedOutputStream(FileOutputStream(out), 1 shl 16)).use { o ->
                // Kopf
                o.writeBytes("8BPS"); o.writeShort(1); o.write(ByteArray(6))
                o.writeShort(3); o.writeInt(h); o.writeInt(w); o.writeShort(8); o.writeShort(3)
                // Farbmodusdaten
                o.writeInt(0)
                // Bildressourcen: Auflösung (0x03ED)
                val res = ByteArrayOutputStream()
                DataOutputStream(res).apply {
                    writeBytes("8BIM"); writeShort(0x03ED); writeShort(0); writeInt(16)
                    val fixed = doc.dpi shl 16
                    writeInt(fixed); writeShort(1); writeShort(1)
                    writeInt(fixed); writeShort(1); writeShort(1)
                }
                o.writeInt(res.size()); res.writeTo(o)

                // Ebenen-Records in Puffer, um Längen zu kennen
                val recBuf = ByteArrayOutputStream()
                val rd = DataOutputStream(recBuf)
                rd.writeShort(recs.size)
                for (r in recs) writeRecord(r, rd)
                rd.flush()
                var layerInfoLen = recBuf.size().toLong() + chanFile.length()
                val pad = ((4 - layerInfoLen % 4) % 4).toInt()
                layerInfoLen += pad
                o.writeInt((4 + layerInfoLen + 4).toInt())   // Ebenen- und Maskeninfo
                o.writeInt(layerInfoLen.toInt())
                recBuf.writeTo(o)
                BufferedInputStream(FileInputStream(chanFile), 1 shl 16).use { it.copyTo(o, 1 shl 16) }
                repeat(pad) { o.write(0) }
                o.writeInt(0) // globale Maskeninfo

                // Zusammengefügtes Bild (RLE): erst alle Zeilenlängen, dann Daten
                writeComposite(composite, o)
            }
        } finally {
            chanFile.delete()
        }
    }

    private fun encodeRecord(r: Rec, w: Int, h: Int, enc: ChannelEncoder, cd: DataOutputStream) {
        val layer = r.layer
        val fill = r.fillColor
        when {
            fill != null -> { r.top = 0; r.left = 0; r.bottom = h; r.right = w }
            layer != null -> {
                val b = contentBounds(layer.bitmap)
                if (b != null) { r.left = b[0]; r.top = b[1]; r.right = b[2]; r.bottom = b[3] }
            }
        }
        val ids = intArrayOf(-1, 0, 1, 2)
        for (cid in ids) {
            r.channelIds += cid
            if (r.right <= r.left || r.bottom <= r.top) {
                cd.writeShort(0); r.channelLens += 2L   // leer: nur Kompressionskennung
            } else if (fill != null) {
                r.channelLens += enc.encodeConstant(cd, r.right - r.left, r.bottom - r.top, channelOf(fill, cid))
            } else {
                r.channelLens += enc.encodeBitmap(cd, layer!!.bitmap, r.left, r.top, r.right, r.bottom, cid)
            }
        }
        val mask = layer?.mask
        if (mask != null) {
            r.hasMask = true
            r.channelIds += -2
            r.channelLens += enc.encodeBitmap(cd, mask, 0, 0, mask.width, mask.height,
                if (mask.config == android.graphics.Bitmap.Config.ALPHA_8) -1 else 2 /* Graustufen: Helligkeit */)
        }
        layer?.compactPixels()
    }

    private fun channelOf(argb: Int, cid: Int) = when (cid) {
        -1 -> Color.alpha(argb); 0 -> Color.red(argb); 1 -> Color.green(argb); else -> Color.blue(argb)
    }

    private fun writeRecord(r: Rec, o: DataOutputStream) {
        o.writeInt(r.top); o.writeInt(r.left); o.writeInt(r.bottom); o.writeInt(r.right)
        o.writeShort(r.channelIds.size)
        for (k in r.channelIds.indices) { o.writeShort(r.channelIds[k]); o.writeInt(r.channelLens[k].toInt()) }
        o.writeBytes("8BIM")
        val l = r.layer
        o.writeBytes(if (l != null) blendKey(l.blend) else if (r.sectionType == 1) "pass" else "norm")
        o.write(((l?.opacity ?: r.groupOpacity) * 255f + 0.5f).toInt().coerceIn(0, 255))
        o.write(if (l?.clipping == true) 1 else 0)
        var flags = 0
        if (l?.alphaLock == true) flags = flags or 0x01
        val visible = l?.visible ?: r.visible
        if (!visible) flags = flags or 0x02
        if (r.sectionType != 0) flags = flags or 0x18   // Bit 3+4: "Pixeldaten irrelevant"
        o.write(flags)
        o.write(0)

        val extra = ByteArrayOutputStream()
        val e = DataOutputStream(extra)
        // Ebenenmaske
        if (r.hasMask && l?.mask != null) {
            val m = l.mask!!
            e.writeInt(20)
            e.writeInt(0); e.writeInt(0); e.writeInt(m.height); e.writeInt(m.width)
            e.write(0)   // Standardfarbe außerhalb: schwarz (verdeckt)
            e.write(0)   // Flags
            e.writeShort(0)
        } else e.writeInt(0)
        // Mischbereiche (leer)
        e.writeInt(0)
        // Pascal-Name, auf 4 Bytes aufgefüllt
        val ascii = r.name.map { c -> if (c.code in 32..126) c.code.toByte() else '?'.code.toByte() }
            .take(255).toByteArray()
        e.write(ascii.size); e.write(ascii)
        val nameLen = 1 + ascii.size
        repeat((4 - nameLen % 4) % 4) { e.write(0) }
        // Unicode-Name
        val uni = ByteArrayOutputStream()
        DataOutputStream(uni).apply { writeInt(r.name.length); r.name.forEach { writeChar(it.code) } }
        writeBlock(e, "luni", uni.toByteArray())
        // Schutz-Flags
        val protect = when {
            l?.locked == true -> 0x80000000.toInt()
            l?.alphaLock == true -> 0x01
            else -> 0
        }
        writeBlock(e, "lspf", byteArrayOf((protect ushr 24).toByte(), (protect ushr 16).toByte(), (protect ushr 8).toByte(), protect.toByte()))
        if (r.sectionType != 0) {
            val s = ByteArrayOutputStream()
            DataOutputStream(s).apply { writeInt(r.sectionType); if (r.sectionType == 1) { writeBytes("8BIM"); writeBytes("pass") } }
            writeBlock(e, "lsct", s.toByteArray())
        }
        e.flush()
        o.writeInt(extra.size()); extra.writeTo(o)
    }

    /** Zusatzinfo-Block "8BIM key len data", Daten auf 4 Bytes aufgefüllt. */
    private fun writeBlock(o: DataOutputStream, key: String, data: ByteArray) {
        val padded = (data.size + 3) / 4 * 4
        o.writeBytes("8BIM"); o.writeBytes(key); o.writeInt(padded)
        o.write(data); repeat(padded - data.size) { o.write(0) }
    }

    private fun writeComposite(bmp: Bitmap, o: DataOutputStream) {
        val w = bmp.width; val h = bmp.height
        val row = IntArray(w)
        val raw = ByteArray(w)
        val packed = ByteArray(w + w / 128 + 4)
        // Durchlauf 1: Zeilenlängen
        o.writeShort(1)
        val lens = ShortArray(3 * h)
        for (c in 0 until 3) for (y in 0 until h) {
            compositeRow(bmp, y, c, row, raw)
            lens[c * h + y] = PackBits.encode(raw, w, packed).toShort()
        }
        for (s in lens) o.writeShort(s.toInt())
        // Durchlauf 2: Daten
        for (c in 0 until 3) for (y in 0 until h) {
            compositeRow(bmp, y, c, row, raw)
            val n = PackBits.encode(raw, w, packed)
            o.write(packed, 0, n)
        }
    }

    private fun compositeRow(bmp: Bitmap, y: Int, c: Int, row: IntArray, raw: ByteArray) {
        bmp.getPixels(row, 0, bmp.width, 0, y, bmp.width, 1)
        val shift = 16 - 8 * c
        for (x in row.indices) {
            val p = row[x]
            val a = p ushr 24
            // Auf Weiß zusammenfügen (das Composite hat keinen Alphakanal)
            val v = (p ushr shift) and 0xFF
            raw[x] = ((v * a + 255 * (255 - a) + 127) / 255).toByte()
        }
    }

    /** Begrenzungsrechteck der nicht transparenten Pixel [l, t, r, b] oder null bei leerer Ebene. */
    fun contentBounds(bmp: Bitmap): IntArray? {
        val w = bmp.width; val h = bmp.height
        val row = IntArray(w)
        var minX = w; var minY = h; var maxX = -1; var maxY = -1
        for (y in 0 until h) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            var first = -1; var last = -1
            for (x in 0 until w) if (row[x] ushr 24 != 0) { first = x; break }
            if (first < 0) continue
            for (x in w - 1 downTo first) if (row[x] ushr 24 != 0) { last = x; break }
            if (y < minY) minY = y
            maxY = y
            if (first < minX) minX = first
            if (last > maxX) maxX = last
        }
        return if (maxX < 0) null else intArrayOf(minX, minY, maxX + 1, maxY + 1)
    }

    /** Kodiert Kanäle als RLE (Kompression 1). Puffer werden wiederverwendet. */
    private class ChannelEncoder(maxW: Int) {
        private var row = IntArray(maxW)
        private var raw = ByteArray(maxW)
        private var packed = ByteArray(maxW + maxW / 128 + 4)
        private val data = ByteArrayOutputStream(1 shl 16)

        private fun ensure(w: Int) {
            if (row.size < w) { row = IntArray(w); raw = ByteArray(w); packed = ByteArray(w + w / 128 + 4) }
        }

        /** @param cid -1 = Alpha, 0..2 = R/G/B. Liefert die geschriebene Länge inkl. Kompressionskennung. */
        fun encodeBitmap(o: DataOutputStream, bmp: Bitmap, l: Int, t: Int, r: Int, b: Int, cid: Int): Long {
            val w = r - l; val h = b - t
            ensure(w)
            data.reset()
            val counts = ShortArray(h)
            val shift = when (cid) { -1 -> 24; 0 -> 16; 1 -> 8; else -> 0 }
            for (y in 0 until h) {
                bmp.getPixels(row, 0, w, l, t + y, w, 1)
                for (x in 0 until w) raw[x] = (row[x] ushr shift).toByte()
                val n = PackBits.encode(raw, w, packed)
                counts[y] = n.toShort()
                data.write(packed, 0, n)
            }
            o.writeShort(1)
            for (c in counts) o.writeShort(c.toInt())
            data.writeTo(o)
            return 2L + 2L * h + data.size()
        }

        fun encodeConstant(o: DataOutputStream, w: Int, h: Int, value: Int): Long {
            ensure(w)
            java.util.Arrays.fill(raw, 0, w, value.toByte())
            val n = PackBits.encode(raw, w, packed)
            o.writeShort(1)
            repeat(h) { o.writeShort(n) }
            repeat(h) { o.write(packed, 0, n) }
            return 2L + 2L * h + n.toLong() * h
        }
    }

    const val BACKGROUND_NAME = "Hintergrundfarbe"
}

/** PackBits-RLE (Apple/PSD/TIFF). */
object PackBits {
    /** Kodiert [len] Bytes aus [src] nach [dst] und liefert die Ausgabelänge. */
    fun encode(src: ByteArray, len: Int, dst: ByteArray): Int {
        var i = 0; var o = 0
        while (i < len) {
            // Wiederholung suchen
            var run = 1
            while (i + run < len && run < 128 && src[i + run] == src[i]) run++
            if (run >= 3 || (run == 2 && i + run >= len)) {
                dst[o++] = (1 - run).toByte()
                dst[o++] = src[i]
                i += run
            } else {
                // Literalfolge bis zur nächsten Wiederholung (≥3) oder max. 128
                val start = i
                var lit = 0
                while (i < len && lit < 128) {
                    if (i + 2 < len && src[i] == src[i + 1] && src[i] == src[i + 2]) break
                    i++; lit++
                }
                dst[o++] = (lit - 1).toByte()
                System.arraycopy(src, start, dst, o, lit)
                o += lit
            }
        }
        return o
    }

    /** Dekodiert in [dst] (genau [outLen] Bytes) ab [pos] in [src]. Liefert die neue Position. */
    fun decode(src: ByteArray, pos: Int, end: Int, dst: ByteArray, dstOff: Int, outLen: Int): Int {
        var p = pos; var o = dstOff
        val oEnd = dstOff + outLen
        while (o < oEnd && p < end) {
            val n = src[p++].toInt()
            if (n >= 0) {
                val cnt = minOf(n + 1, oEnd - o, end - p)
                System.arraycopy(src, p, dst, o, cnt); p += n + 1; o += cnt
            } else if (n != -128) {
                val cnt = minOf(1 - n, oEnd - o)
                val v = src[p++]
                java.util.Arrays.fill(dst, o, o + cnt, v); o += cnt
            }
        }
        return p
    }
}
