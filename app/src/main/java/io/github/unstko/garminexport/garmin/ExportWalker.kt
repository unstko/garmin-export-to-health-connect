package io.github.unstko.garminexport.garmin

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Walks a Garmin export ZIP, descending into the ZIP archives nested inside it
 * (`UploadedFiles_*.zip` holds the FIT files), and hands every file of interest to
 * [visit] as name and content. Nothing is extracted to disk.
 */
object ExportWalker {
    fun walk(input: InputStream, visit: (name: String, bytes: ByteArray) -> Unit) {
        ZipInputStream(input).use { zip -> walkZip(zip, visit) }
    }

    private fun walkZip(zip: ZipInputStream, visit: (String, ByteArray) -> Unit) {
        while (true) {
            val entry = zip.nextEntry ?: break
            if (entry.isDirectory) continue
            val name = entry.name
            val lower = name.lowercase()
            when {
                lower.endsWith(".zip") ->
                    ZipInputStream(ByteArrayInputStream(zip.readBytes())).use { walkZip(it, visit) }
                lower.endsWith(".fit") || GarminExport.isWantedJson(name) ->
                    visit(name, zip.readBytes())
            }
        }
    }
}
