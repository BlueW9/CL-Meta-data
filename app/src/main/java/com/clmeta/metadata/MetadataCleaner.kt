package com.clmeta.metadata

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

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
