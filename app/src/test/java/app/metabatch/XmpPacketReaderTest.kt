package app.metabatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer

class XmpPacketReaderTest {
    private val packet = """<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about=""
 xmlns:dc="http://purl.org/dc/elements/1.1/"
 xmlns:photoshop="http://ns.adobe.com/photoshop/1.0/">
<dc:title><rdf:Alt><rdf:li xml:lang="x-default">VAM日落</rdf:li></rdf:Alt></dc:title><dc:description><rdf:Alt><rdf:li xml:lang="x-default">傍晚的海边</rdf:li></rdf:Alt></dc:description>
</rdf:Description>
</rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""

    @Test fun decodesUtf8Packet() {
        val fields = MetadataReader.parseXmp(XmpPacketReader.decode(packet.toByteArray(Charsets.UTF_8)))
        assertEquals("VAM日落", fields.title)
    }

    @Test fun decodesGbkPacketInsteadOfMojibake() {
        val gbkBytes = packet.toByteArray(charset("GBK"))
        // Sanity: decoding those bytes as UTF-8 really is broken (the reported bug).
        assertTrue(String(gbkBytes, Charsets.UTF_8).contains('\uFFFD'))
        val fields = MetadataReader.parseXmp(XmpPacketReader.decode(gbkBytes))
        assertEquals("VAM日落", fields.title)
        assertEquals("傍晚的海边", fields.description)
    }

    @Test fun decodesUtf16LePacketWithBom() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
            packet.toByteArray(Charsets.UTF_16LE)
        val fields = MetadataReader.parseXmp(XmpPacketReader.decode(bytes))
        assertEquals("VAM日落", fields.title)
    }

    @Test fun extractsPacketFromSyntheticJpeg() {
        val packetBytes = packet.toByteArray(Charsets.UTF_8)
        val header = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.US_ASCII)
        val app1 = header + packetBytes
        val jpeg = ByteBuffer.allocate(2 + 2 + 2 + app1.size + 2).apply {
            put(0xFF.toByte()); put(0xD8.toByte()) // SOI
            put(0xFF.toByte()); put(0xE1.toByte()) // APP1
            putShort((app1.size + 2).toShort())
            put(app1)
            put(0xFF.toByte()); put(0xD9.toByte()) // EOI
        }.array()
        val found = XmpPacketReader.extractBytes(ByteArrayInputStream(jpeg), "image/jpeg")
        assertEquals(packet, found?.let { String(it, Charsets.UTF_8) })
    }

    @Test fun returnsNullWhenNoXmpSegment() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte())
        assertEquals(null, XmpPacketReader.extractBytes(ByteArrayInputStream(jpeg), "image/jpeg"))
    }
}
