package com.clmeta.metadata

import android.content.Context
import android.net.Uri
import android.util.Xml
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationMarkup
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayInputStream
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.zip.ZipInputStream

data class MetaEntry(
    val group: String,
    val label: String,
    val value: String,
    val sensitive: Boolean = false
)

object MetaReader {

    private class F(val key: String, val group: String, val label: String, val sens: Boolean = false)

    private val CORE = listOf(
        F("creator", "Identity", "Author", true),
        F("lastModifiedBy", "Identity", "Last modified by", true),
        F("title", "Identity", "Title"),
        F("subject", "Identity", "Subject"),
        F("keywords", "Identity", "Keywords"),
        F("description", "Identity", "Comments"),
        F("category", "Identity", "Category"),
        F("created", "Dates", "Created"),
        F("modified", "Dates", "Last modified"),
        F("lastPrinted", "Dates", "Last printed"),
        F("revision", "Dates", "Revision"),
        F("contentStatus", "Format", "Status"),
        F("language", "Format", "Language")
    )

    private val APP = listOf(
        F("Application", "Software", "Application", true),
        F("AppVersion", "Software", "App version"),
        F("Company", "Identity", "Company", true),
        F("Manager", "Identity", "Manager", true),
        F("Template", "Software", "Template"),
        F("TotalTime", "Dates", "Editing time (min)"),
        F("Pages", "Format", "Pages"),
        F("Words", "Format", "Words"),
        F("Slides", "Format", "Slides"),
        F("HiddenSlides", "Hidden", "Hidden slides"),
        F("DocSecurity", "Format", "Security flag")
    )

    fun read(ctx: Context, uri: Uri): List<MetaEntry> {
        val b = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: return emptyList()
        if (b.size < 8) return emptyList()
        return try {
            when {
                String(b, 0, 5, Charsets.ISO_8859_1) == "%PDF-" -> readPdf(b)
                b[0] == 0x50.toByte() && b[1] == 0x4B.toByte() -> readOoxml(b)
                b[0] == 0xD0.toByte() && b[1] == 0xCF.toByte() ->
                    listOf(MetaEntry("Format", "Type", "Legacy Office (doc, xls, ppt)"))
                else -> emptyList()
            }
        } catch (e: Exception) {
            listOf(MetaEntry("Error", "Could not read file", e.javaClass.simpleName))
        }
    }

    private fun add(o: MutableList<MetaEntry>, g: String, l: String, v: String?, s: Boolean = false) {
        if (!v.isNullOrBlank()) o.add(MetaEntry(g, l, v.trim(), s))
    }

    private fun cal(c: Calendar?): String? = c?.let {
        SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.US).apply { timeZone = it.timeZone }.format(it.time)
    }

    private fun iso(s: String): String =
        if (Regex("^\\d{4}-\\d{2}-\\d{2}T.*").matches(s)) s.replace("T", " ").removeSuffix("Z") + " UTC" else s

    private fun xmp(x: String, tag: String): String? {
        val el = Regex("<$tag[^>]*>(.*?)</$tag>", RegexOption.DOT_MATCHES_ALL).find(x)
            ?.groupValues?.get(1)?.replace(Regex("<[^>]+>"), " ")?.replace(Regex("\\s+"), " ")?.trim()
        if (!el.isNullOrBlank()) return el
        return Regex("$tag=\"([^\"]*)\"").find(x)?.groupValues?.get(1)
    }

    private fun readPdf(b: ByteArray): List<MetaEntry> {
        val out = ArrayList<MetaEntry>()
        add(out, "Format", "PDF version", String(b, 5, 3, Charsets.ISO_8859_1))
        val eofs = Regex("%%EOF").findAll(String(b, Charsets.ISO_8859_1)).count()
        PDDocument.load(b).use { d ->
            val i = d.documentInformation
            add(out, "Format", "Pages", d.numberOfPages.toString())
            add(out, "Format", "Encrypted", if (d.isEncrypted) "Yes" else "No")
            add(out, "Identity", "Title", i.title)
            add(out, "Identity", "Author", i.author, true)
            add(out, "Identity", "Subject", i.subject)
            add(out, "Identity", "Keywords", i.keywords)
            add(out, "Software", "Creator", i.creator, true)
            add(out, "Software", "Producer", i.producer, true)
            add(out, "Dates", "Created", cal(i.creationDate))
            add(out, "Dates", "Last modified", cal(i.modificationDate))
            add(out, "Format", "Trapped", i.trapped)
            val std = setOf("Title", "Author", "Subject", "Keywords", "Creator", "Producer", "CreationDate", "ModDate", "Trapped")
            for (k in i.metadataKeys) if (k !in std) add(out, "Custom", k, i.getCustomMetadataValue(k))
            try {
                val x = d.documentCatalog.metadata?.exportXMPMetadata()?.use { String(it.readBytes(), Charsets.UTF_8) }
                if (x != null) {
                    add(out, "XMP", "Creator tool", xmp(x, "xmp:CreatorTool"), true)
                    add(out, "XMP", "Producer", xmp(x, "pdf:Producer"), true)
                    add(out, "XMP", "Author", xmp(x, "dc:creator"), true)
                    add(out, "XMP", "Title", xmp(x, "dc:title"))
                    add(out, "XMP", "Created", xmp(x, "xmp:CreateDate")?.let { iso(it) })
                    add(out, "XMP", "Modified", xmp(x, "xmp:ModifyDate")?.let { iso(it) })
                    add(out, "XMP", "Metadata date", xmp(x, "xmp:MetadataDate")?.let { iso(it) })
                }
            } catch (_: Exception) {}
            try {
                val authors = sortedSetOf<String>()
                for (p in d.pages) for (a in p.annotations)
                    if (a is PDAnnotationMarkup) a.titlePopup?.let { authors.add(it) }
                if (authors.isNotEmpty()) add(out, "Hidden", "Comment authors", authors.joinToString(", "), true)
            } catch (_: Exception) {}
        }
        if (eofs > 1) add(out, "Hidden", "Save markers", "$eofs (may contain older versions)")
        return out
    }

    private fun parseFlat(xml: ByteArray): Map<String, String> {
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(xml), null)
        val m = linkedMapOf<String, String>()
        var depth = 0
        var cur: String? = null
        var e = p.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            when (e) {
                XmlPullParser.START_TAG -> { depth++; if (depth == 2) cur = p.name }
                XmlPullParser.TEXT -> if (depth == 2 && cur != null) {
                    val t = p.text.trim()
                    if (t.isNotEmpty()) m[cur!!] = t
                }
                XmlPullParser.END_TAG -> { if (depth == 2) cur = null; depth-- }
            }
            e = p.next()
        }
        return m
    }

    private fun parseCustom(xml: ByteArray): Map<String, String> {
        val p = Xml.newPullParser()
        p.setInput(ByteArrayInputStream(xml), null)
        val m = linkedMapOf<String, String>()
        var name: String? = null
        var e = p.eventType
        while (e != XmlPullParser.END_DOCUMENT) {
            when (e) {
                XmlPullParser.START_TAG -> if (p.name == "property") name = p.getAttributeValue(null, "name")
                XmlPullParser.TEXT -> if (name != null && p.text.trim().isNotEmpty()) m[name!!] = p.text.trim()
                XmlPullParser.END_TAG -> if (p.name == "property") name = null
            }
            e = p.next()
        }
        return m
    }

    private fun readOoxml(b: ByteArray): List<MetaEntry> {
        val out = ArrayList<MetaEntry>()
        val xml = HashMap<String, ByteArray>()
        var type: String? = null
        var media = 0
        var tracked = 0
        var comments = false
        var thumb = false
        val cmt = Regex("(word/comments.*|xl/comments.*|ppt/comments/.*)\\.xml")
        ZipInputStream(ByteArrayInputStream(b)).use { z ->
            var e = z.nextEntry
            while (e != null) {
                val n = e.name
                if (type == null) type = when {
                    n.startsWith("word/") -> "Word (docx)"
                    n.startsWith("xl/") -> "Excel (xlsx)"
                    n.startsWith("ppt/") -> "PowerPoint (pptx)"
                    else -> null
                }
                when {
                    n == "docProps/core.xml" || n == "docProps/app.xml" || n == "docProps/custom.xml" ->
                        xml[n] = z.readBytes()
                    n.startsWith("docProps/thumbnail") -> thumb = true
                    n.contains("/media/") && !e.isDirectory -> media++
                    cmt.matches(n) -> comments = true
                    n == "word/document.xml" -> {
                        val s = String(z.readBytes(), Charsets.UTF_8)
                        tracked = Regex("<w:ins ").findAll(s).count() + Regex("<w:del ").findAll(s).count()
                    }
                }
                e = z.nextEntry
            }
        }
        add(out, "Format", "Type", type ?: "Office Open XML")
        xml["docProps/core.xml"]?.let { x ->
            val m = parseFlat(x)
            for (f in CORE) add(out, f.group, f.label, m[f.key]?.let { if (f.group == "Dates") iso(it) else it }, f.sens)
        }
        xml["docProps/app.xml"]?.let { x ->
            val m = parseFlat(x)
            for (f in APP) add(out, f.group, f.label, m[f.key], f.sens)
        }
        xml["docProps/custom.xml"]?.let { x -> for ((k, v) in parseCustom(x)) add(out, "Custom", k, v) }
        if (comments) add(out, "Hidden", "Comments", "Present", true)
        if (tracked > 0) add(out, "Hidden", "Tracked changes", "$tracked (with author names)", true)
        if (thumb) add(out, "Hidden", "Preview thumbnail", "Present")
        if (media > 0) add(out, "Hidden", "Embedded media files", media.toString())
        return out
    }
}
