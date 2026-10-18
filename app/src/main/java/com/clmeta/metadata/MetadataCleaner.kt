package com.clmeta.metadata

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

data class MetaField(val label: String, val value: String)

class CleanReport(val before: List<MetaField>, val after: List<MetaField>, val changed: Boolean)

object MetadataCleaner {

    val supported = arrayOf(
        "application/pdf",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation"
    )

    private val mimeByExt = mapOf(
        "pdf" to supported[0], "docx" to supported[1], "xlsx" to supported[2], "pptx" to supported[3]
    )

    private const val EMPTY_CORE =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<cp:coreProperties xmlns:cp=\"http://schemas.openxmlformats.org/package/2006/metadata/core-properties\" " +
        "xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:dcterms=\"http://purl.org/dc/terms/\" " +
        "xmlns:dcmitype=\"http://purl.org/dc/dcmitype/\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"/>"

    private const val EMPTY_APP =
        "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
        "<Properties xmlns=\"http://schemas.openxmlformats.org/officeDocument/2006/extended-properties\"/>"

    private val relRegex = Regex("<Relationship\\b[^>]*?(thumbnail|custom\\.xml)[^>]*?/>", RegexOption.IGNORE_CASE)
    private val ctRegex = Regex("<Override\\b[^>]*?custom\\.xml[^>]*?/>", RegexOption.IGNORE_CASE)

    fun extensionOf(name: String) = name.substringAfterLast('.', "").lowercase()
    fun isSupported(name: String) = extensionOf(name) in mimeByExt

    // ---------------- inspection (what metadata does the file contain?) ----------------

    private val coreTags = listOf(
        "creator" to "Author", "lastModifiedBy" to "Last modified by", "title" to "Title",
        "subject" to "Subject", "keywords" to "Keywords", "description" to "Comments",
        "category" to "Category", "contentStatus" to "Status", "identifier" to "Identifier",
        "created" to "Created on", "modified" to "Modified on", "lastPrinted" to "Last printed",
        "revision" to "Revision count"
    )
    private val dateTags = setOf("created", "modified", "lastPrinted")
    private val appTags = listOf(
        "Application" to "Created with", "AppVersion" to "App version", "Company" to "Company",
        "Manager" to "Manager", "Template" to "Template", "HyperlinkBase" to "Hyperlink base"
    )
    private val statTags = listOf(
        "Pages", "Words", "Characters", "CharactersWithSpaces", "Lines", "Paragraphs",
        "Slides", "Notes", "HiddenSlides", "TotalTime"
    )
    private val pdfDateRegex = Regex("""D:(\d{4})(\d{2})?(\d{2})?(\d{2})?(\d{2})?(\d{2})?""")

    /** Lists the metadata found in the file. An empty list means the file is clean. */
    fun inspect(ctx: Context, input: Uri, name: String): List<MetaField> {
        val ext = extensionOf(name)
        if (ext !in mimeByExt) error("Unsupported file type (.$ext)")
        val ins = ctx.contentResolver.openInputStream(input) ?: error("Cannot read the file")
        return ins.use { if (ext == "pdf") inspectPdf(it) else inspectOoxml(it) }
    }

    /** Inspects, cleans in place only if needed, then inspects again to verify. */
    fun cleanAndReport(ctx: Context, input: Uri, name: String): CleanReport {
        val before = inspect(ctx, input, name)
        if (before.isEmpty()) return CleanReport(before, before, false)
        clean(ctx, input, name)
        val after = inspect(ctx, input, name)
        return CleanReport(before, after, true)
    }

    private fun shorten(v: String): String {
        val t = v.trim().replace(Regex("\\s+"), " ")
        return if (t.length > 70) t.take(70) + "..." else t
    }

    private fun pdfDate(v: String): String {
        val m = pdfDateRegex.find(v) ?: return v
        val g = m.groupValues
        val date = listOf(g[1], g[2].ifEmpty { "01" }, g[3].ifEmpty { "01" }).joinToString("-")
        return if (g[4].isNotEmpty()) "$date ${g[4]}:${g[5].ifEmpty { "00" }}" else date
    }

    private fun pdfLabel(k: String) = when (k) {
        "Creator" -> "Created with"
        "Producer" -> "PDF producer"
        "CreationDate" -> "Created on"
        "ModDate" -> "Modified on"
        else -> k
    }

    private fun inspectPdf(ins: InputStream): List<MetaField> {
        val out = ArrayList<MetaField>()
        PDDocument.load(ins).use { doc ->
            if (doc.isEncrypted) error("Encrypted PDF is not supported")
            val info = doc.documentInformation
            for (key in info.metadataKeys) {
                val v = info.getCustomMetadataValue(key)
                if (!v.isNullOrBlank()) {
                    val shown = if (key.endsWith("Date")) pdfDate(v) else v
                    out.add(MetaField(pdfLabel(key), shorten(shown)))
                }
            }
            if (doc.documentCatalog.metadata != null) out.add(MetaField("XMP metadata", "embedded block"))
        }
        return out
    }

    private fun xmlValue(xml: String, tag: String): String? {
        val m = Regex("<(?:[A-Za-z0-9]+:)?$tag\\b[^>]*>([^<]*)</(?:[A-Za-z0-9]+:)?$tag>").find(xml) ?: return null
        val v = m.groupValues[1]
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&apos;", "'").replace("&amp;", "&").trim()
        return v.ifEmpty { null }
    }

    private fun inspectOoxml(ins: InputStream): List<MetaField> {
        var core: String? = null
        var app: String? = null
        var custom: String? = null
        var thumbnail = false
        ZipInputStream(ins).use { zin ->
            var e: ZipEntry? = zin.nextEntry
            while (e != null) {
                val n = e.name
                when {
                    n.equals("docProps/core.xml", true) -> core = String(zin.readBytes(), Charsets.UTF_8)
                    n.equals("docProps/app.xml", true) -> app = String(zin.readBytes(), Charsets.UTF_8)
                    n.equals("docProps/custom.xml", true) -> custom = String(zin.readBytes(), Charsets.UTF_8)
                    n.startsWith("docProps/thumbnail", true) -> thumbnail = true
                }
                zin.closeEntry()
                e = zin.nextEntry
            }
        }
        val out = ArrayList<MetaField>()
        core?.let { xml ->
            for ((tag, label) in coreTags) {
                val v = xmlValue(xml, tag) ?: continue
                val shown = if (tag in dateTags) v.replace('T', ' ').removeSuffix("Z") else v
                out.add(MetaField(label, shorten(shown)))
            }
        }
        app?.let { xml ->
            for ((tag, label) in appTags) {
                val v = xmlValue(xml, tag) ?: continue
                out.add(MetaField(label, shorten(v)))
            }
            val stats = statTags.mapNotNull { t -> xmlValue(xml, t)?.let { "$t $it" } }
            if (stats.isNotEmpty()) out.add(MetaField("Document statistics", shorten(stats.joinToString(", "))))
        }
        custom?.let { xml ->
            val names = Regex("<property\\b[^>]*?name=\"([^\"]+)\"").findAll(xml).map { it.groupValues[1] }.toList()
            out.add(MetaField("Custom properties", if (names.isEmpty()) "present" else shorten(names.joinToString(", "))))
        }
        if (thumbnail) out.add(MetaField("Thumbnail image", "embedded preview"))
        return out
    }

    /**
     * Cleans the file IN PLACE. No copy is created next to it.
     *
     * To make sure the original can never be lost half-way:
     * 1. the original bytes are kept in the app's private cache while we work,
     * 2. the cleaned version is built and verified in the cache,
     * 3. only then is the original overwritten,
     * 4. if overwriting fails, the original bytes are written back.
     * The temporary files are always deleted at the end.
     */
    fun clean(ctx: Context, input: Uri, name: String) {
        val ext = extensionOf(name)
        if (ext !in mimeByExt) error("Unsupported file type (.$ext)")
        val cr = ctx.contentResolver
        val backup = File.createTempFile("orig_", ".tmp", ctx.cacheDir)
        val cleaned = File.createTempFile("clean_", ".tmp", ctx.cacheDir)
        try {
            val ins = cr.openInputStream(input) ?: error("Cannot read the file")
            ins.use { src -> FileOutputStream(backup).use { dst -> src.copyTo(dst) } }

            if (ext == "pdf") cleanPdf(backup, cleaned) else cleanOoxml(backup, cleaned)

            val out = (try {
                cr.openOutputStream(input, "wt")
            } catch (e: Throwable) {
                null
            }) ?: error("This file is read-only for the app, so nothing was changed")

            try {
                out.use { o -> FileInputStream(cleaned).use { s -> s.copyTo(o) } }
            } catch (e: Throwable) {
                val restored = try {
                    writeBack(cr, input, backup)
                    true
                } catch (e2: Throwable) {
                    false
                }
                error(
                    if (restored) "Could not save the cleaned file. The original was restored unchanged"
                    else "Saving failed and the original may be damaged"
                )
            }
        } finally {
            backup.delete()
            cleaned.delete()
        }
    }

    private fun writeBack(cr: ContentResolver, uri: Uri, from: File) {
        val o = cr.openOutputStream(uri, "wt") ?: error("Cannot write")
        o.use { s -> FileInputStream(from).use { it.copyTo(s) } }
    }

    private fun cleanPdf(src: File, dst: File) {
        var pages = 0
        PDDocument.load(src).use { doc ->
            if (doc.isEncrypted) error("Encrypted PDF is not supported")
            pages = doc.numberOfPages
            doc.documentInformation = PDDocumentInformation()
            doc.documentCatalog.metadata = null
            FileOutputStream(dst).use { doc.save(it) }
        }
        // Verify the result opens and has the same number of pages before touching the original.
        PDDocument.load(dst).use { check(it.numberOfPages == pages) { "Verification failed" } }
    }

    private fun cleanOoxml(src: File, dst: File) {
        var written = 0
        ZipInputStream(FileInputStream(src).buffered()).use { zin ->
            ZipOutputStream(FileOutputStream(dst).buffered()).use { zout ->
                var e: ZipEntry? = zin.nextEntry
                while (e != null) {
                    val n = e.name
                    val skip = n.equals("docProps/custom.xml", true) ||
                            n.startsWith("docProps/thumbnail", true)
                    if (!skip) {
                        zout.putNextEntry(ZipEntry(n))
                        written++
                        if (!e.isDirectory) {
                            when (n) {
                                "docProps/core.xml" -> zout.write(EMPTY_CORE.toByteArray())
                                "docProps/app.xml" -> zout.write(EMPTY_APP.toByteArray())
                                "_rels/.rels" -> zout.write(
                                    relRegex.replace(String(zin.readBytes(), Charsets.UTF_8), "").toByteArray())
                                "[Content_Types].xml" -> zout.write(
                                    ctRegex.replace(String(zin.readBytes(), Charsets.UTF_8), "").toByteArray())
                                else -> zin.copyTo(zout)
                            }
                        }
                        zout.closeEntry()
                    }
                    zin.closeEntry()
                    e = zin.nextEntry
                }
            }
        }
        // Verify the result is a valid package with every entry before touching the original.
        ZipFile(dst).use { z ->
            check(z.size() == written && z.getEntry("[Content_Types].xml") != null) { "Verification failed" }
        }
    }
}
