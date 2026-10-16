package com.clmeta.metadata

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDDocumentInformation
import java.util.zip.ZipEntry
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

    fun clean(ctx: Context, input: Uri, name: String, outDir: DocumentFile) {
        val ext = extensionOf(name)
        val mime = mimeByExt[ext] ?: error("Unsupported file type (.$ext)")
        val outName = name.substringBeforeLast('.') + "_clean." + ext
        outDir.findFile(outName)?.delete()
        val outFile = outDir.createFile(mime, outName) ?: error("Cannot create output file")
        try {
            ctx.contentResolver.openInputStream(input)!!.use { ins ->
                ctx.contentResolver.openOutputStream(outFile.uri, "w")!!.use { outs ->
                    if (ext == "pdf") cleanPdf(ins, outs) else cleanOoxml(ins, outs)
                }
            }
        } catch (t: Throwable) {
            outFile.delete()
            throw t
        }
    }

    private fun cleanPdf(ins: java.io.InputStream, outs: java.io.OutputStream) {
        PDDocument.load(ins).use { doc ->
            if (doc.isEncrypted) error("Encrypted PDF is not supported")
            doc.documentInformation = PDDocumentInformation()
            doc.documentCatalog.metadata = null
            doc.save(outs)
        }
    }

    private fun cleanOoxml(ins: java.io.InputStream, outs: java.io.OutputStream) {
        ZipInputStream(ins).use { zin ->
            ZipOutputStream(outs).use { zout ->
                var e: ZipEntry? = zin.nextEntry
                while (e != null) {
                    val n = e.name
                    val skip = n.equals("docProps/custom.xml", true) ||
                            n.startsWith("docProps/thumbnail", true)
                    if (!skip) {
                        zout.putNextEntry(ZipEntry(n))
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
    }
}
