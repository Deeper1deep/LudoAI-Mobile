package com.ludoai.assistant

import com.ludoai.assistant.core.Frame
import java.io.DataInputStream
import java.util.zip.GZIPInputStream

object Fixtures {
    /** Fixture format: gzip( u32 w, u32 h, w*h*3 RGB bytes ). */
    fun load(name: String): Frame {
        val raw = DataInputStream(GZIPInputStream(Fixtures::class.java.getResourceAsStream("/$name")!!))
        val w = raw.readInt(); val h = raw.readInt()
        val bytes = ByteArray(w * h * 3); raw.readFully(bytes)
        val px = IntArray(w * h) { i ->
            (0xFF shl 24) or ((bytes[i * 3].toInt() and 255) shl 16) or ((bytes[i * 3 + 1].toInt() and 255) shl 8) or (bytes[i * 3 + 2].toInt() and 255)
        }
        return Frame(w, h, px)
    }
}
