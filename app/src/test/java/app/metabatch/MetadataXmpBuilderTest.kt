package app.metabatch

import org.junit.Assert.assertTrue
import org.junit.Test

class MetadataXmpBuilderTest {
    @Test fun escapesXmlSpecialCharacters() {
        val packet = MetadataXmpBuilder.build(
            MetadataValues("A & B", "A < B", emptyList(), "R&D", "© 2026", "site?a=1&b=2")
        )
        assertTrue(packet.contains("A &amp; B"))
        assertTrue(packet.contains("A &lt; B"))
        assertTrue(packet.contains("R&amp;D"))
        assertTrue(packet.contains("site?a=1&amp;b=2"))
    }

    @Test fun keywordsRemainSeparateRdfBagItemsAndAreDeduplicated() {
        val packet = MetadataXmpBuilder.build(
            MetadataValues("", "", listOf("风景", "旅行", "风景", "  "), "", "", "")
        )
        assertTrue(packet.contains("<rdf:Bag><rdf:li>风景</rdf:li><rdf:li>旅行</rdf:li></rdf:Bag>"))
    }

    @Test fun blankValuesAreOmitted() {
        val packet = MetadataXmpBuilder.build(MetadataValues("", "", emptyList(), "", "", ""))
        assertTrue(!packet.contains("<dc:title>"))
        assertTrue(!packet.contains("<dc:subject>"))
        assertTrue(!packet.contains("<photoshop:Source>"))
    }
}
