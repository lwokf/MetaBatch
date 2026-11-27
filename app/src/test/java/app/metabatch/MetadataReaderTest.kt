package app.metabatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataReaderTest {
    private val sample = """<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about=""
 xmlns:dc="http://purl.org/dc/elements/1.1/"
 xmlns:photoshop="http://ns.adobe.com/photoshop/1.0/">
<dc:title><rdf:Alt><rdf:li xml:lang="x-default">日落 &amp; 海</rdf:li></rdf:Alt></dc:title><dc:description><rdf:Alt><rdf:li xml:lang="x-default">傍晚的海边</rdf:li></rdf:Alt></dc:description><dc:creator><rdf:Seq><rdf:li>bo</rdf:li></rdf:Seq></dc:creator><dc:rights><rdf:Alt><rdf:li xml:lang="x-default">© 2026 bo</rdf:li></rdf:Alt></dc:rights><dc:subject><rdf:Bag><rdf:li>风景</rdf:li><rdf:li>旅行</rdf:li><rdf:li>风景</rdf:li></rdf:Bag></dc:subject><photoshop:Source>手机相册</photoshop:Source><tiff:Software>ComfyUI - ChillImageSavePlus</tiff:Software>
</rdf:Description>
</rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""

    @Test fun parsesAllXmpFields() {
        val x = MetadataReader.parseXmp(sample)
        assertEquals("日落 & 海", x.title)
        assertEquals("傍晚的海边", x.description)
        assertEquals("bo", x.creator)
        assertEquals("© 2026 bo", x.rights)
        assertEquals(listOf("风景", "旅行"), x.keywords)
        assertEquals("手机相册", x.source)
        assertEquals("ComfyUI - ChillImageSavePlus", x.software)
    }

    @Test fun blankOrMissingXmpGivesEmptyFields() {
        val empty = MetadataReader.parseXmp(null)
        assertTrue(empty.title.isEmpty())
        assertTrue(empty.keywords.isEmpty())
        val partial = MetadataReader.parseXmp("<x:xmpmeta></x:xmpmeta>")
        assertTrue(partial.title.isEmpty())
        assertTrue(partial.source.isEmpty())
    }

    @Test fun mergeXmpWithIptc_xmpWinsIptcFillsBlanks() {
        val xmp = XmpFields(title = "XMP标题", keywords = listOf("x"))
        val iptc = Iptc.IptcFields(
            title = "IPTC标题", description = "IPTC描述", creator = "IPTC作者",
            rights = "IPTC版权", keywords = listOf("i1", "i2"), source = "IPTC来源"
        )
        val merged = MetadataReader.mergeXmpWithIptc(xmp, iptc)
        assertEquals("XMP标题", merged.title)
        assertEquals("IPTC描述", merged.description)
        assertEquals("IPTC作者", merged.creator)
        assertEquals("IPTC版权", merged.rights)
        assertEquals(listOf("x"), merged.keywords)
        assertEquals("IPTC来源", merged.source)
    }

    @Test fun mergeXmpWithIptc_emptyXmpTakesAllIptc() {
        val merged = MetadataReader.mergeXmpWithIptc(
            XmpFields(),
            Iptc.IptcFields(title = "T", keywords = listOf("k"))
        )
        assertEquals("T", merged.title)
        assertEquals(listOf("k"), merged.keywords)
    }

    @Test fun mergeXmpWithIptc_softwareNeverFromIptc() {
        val merged = MetadataReader.mergeXmpWithIptc(
            XmpFields(software = "XMP软件"),
            Iptc.IptcFields(title = "T")
        )
        assertEquals("XMP软件", merged.software)
    }

    @Test fun roundTripWithBuilder() {
        val values = MetadataValues("标题", "描述", listOf("a", "b"), "作者", "版权", "来源", "测试软件")
        val parsed = MetadataReader.parseXmp(MetadataXmpBuilder.build(values))
        assertEquals("标题", parsed.title)
        assertEquals("描述", parsed.description)
        assertEquals("作者", parsed.creator)
        assertEquals("版权", parsed.rights)
        assertEquals(listOf("a", "b"), parsed.keywords)
        assertEquals("来源", parsed.source)
        assertEquals("测试软件", parsed.software)
    }
}
