package app.metabatch

/**
 * Builds an XMP packet containing the seven fields supported by MetaBatch.
 * Kept independent of Android APIs so the serialization rules can be unit-tested.
 */
internal object MetadataXmpBuilder {
    fun build(v: MetadataValues): String {
        fun esc(s: String) = s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")

        val keywordItems = v.keywords
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("") { "<rdf:li>${esc(it)}</rdf:li>" }

        val title = if (v.title.isBlank()) "" else
            "<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">${esc(v.title)}</rdf:li></rdf:Alt></dc:title>"
        val description = if (v.description.isBlank()) "" else
            "<dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">${esc(v.description)}</rdf:li></rdf:Alt></dc:description>"
        val creator = if (v.creator.isBlank()) "" else
            "<dc:creator><rdf:Seq><rdf:li>${esc(v.creator)}</rdf:li></rdf:Seq></dc:creator>"
        val rights = if (v.copyright.isBlank()) "" else
            "<dc:rights><rdf:Alt><rdf:li xml:lang=\"x-default\">${esc(v.copyright)}</rdf:li></rdf:Alt></dc:rights>"
        val subject = if (keywordItems.isBlank()) "" else
            "<dc:subject><rdf:Bag>$keywordItems</rdf:Bag></dc:subject>"
        val source = if (v.source.isBlank()) "" else
            "<photoshop:Source>${esc(v.source)}</photoshop:Source>"
        val software = if (v.software.isBlank()) "" else
            "<tiff:Software>${esc(v.software)}</tiff:Software>"

        return """<?xpacket begin="﻿" id="W5M0MpCehiHzreSzNTczkc9d"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
<rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
<rdf:Description rdf:about=""
 xmlns:dc="http://purl.org/dc/elements/1.1/"
 xmlns:photoshop="http://ns.adobe.com/photoshop/1.0/"
 xmlns:tiff="http://ns.adobe.com/tiff/1.0/">
$title$description$creator$rights$subject$source$software
</rdf:Description>
</rdf:RDF>
</x:xmpmeta>
<?xpacket end="w"?>"""
    }
}
