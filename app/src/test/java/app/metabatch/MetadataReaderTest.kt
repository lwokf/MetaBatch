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
<dc:title><rdf:Alt><rdf:li xml:lang="x-default">日落 &amp; 海</rdf:li></rdf:Alt></dc:title><dc:description><rdf:Alt><rdf:li xml:lang="x-default">傍晚的海边</rdf:li></rdf:Alt></dc:description><dc:creator><rdf:Seq><rdf:li>bo</rdf:li></rdf:Seq></dc:creator><dc:rights><rdf:Alt><rdf:li xml:lang="x-default">© 2026 bo</rdf:li></rdf:Alt></dc:rights><dc:subject><rdf:Bag><rdf:li>风景</rdf:li><rdf:li>旅行</rdf:li><rdf:li>风景</rdf:li></rdf:Bag></dc:subject><photoshop:Source>手机相册</photoshop:Source>
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
    }

    @Test fun blankOrMissingXmpGivesEmptyFields() {
        val empty = MetadataReader.parseXmp(null)
        assertTrue(empty.title.isEmpty())
        assertTrue(empty.keywords.isEmpty())
        val partial = MetadataReader.parseXmp("<x:xmpmeta></x:xmpmeta>")
        assertTrue(partial.title.isEmpty())
        assertTrue(partial.source.isEmpty())
    }

    @Test fun roundTripWithBuilder() {
        val values = MetadataValues("标题", "描述", listOf("a", "b"), "作者", "版权", "来源")
        val parsed = MetadataReader.parseXmp(MetadataXmpBuilder.build(values))
        assertEquals("标题", parsed.title)
        assertEquals("描述", parsed.description)
        assertEquals("作者", parsed.creator)
        assertEquals("版权", parsed.rights)
        assertEquals(listOf("a", "b"), parsed.keywords)
        assertEquals("来源", parsed.source)
    }
}
