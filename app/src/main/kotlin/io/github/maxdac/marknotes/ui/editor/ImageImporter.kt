package io.github.maxdac.marknotes.ui.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.provider.OpenableColumns
import io.github.maxdac.marknotes.core.notes.Attachments
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer

/** An image ready to be stored as an attachment. */
class ImportedImage(val bytes: ByteArray, val extension: String, val fileName: String)

/** A problem with the chosen image, with a message meant for the user. */
class ImageImportException(message: String) : IOException(message)

/**
 * Reads a picked or captured image and prepares it for [Attachments]: only PNG, JPEG, GIF and WebP are
 * accepted, images are downscaled so their longest side is at most [Attachments.MAX_DIMENSION] (keeping
 * their format; GIFs are left untouched) and anything still over [Attachments.MAX_BYTES] is rejected.
 */
class ImageImporter(private val context: Context) {

    fun import(uri: Uri, fallbackName: String, mimeHint: String? = null): ImportedImage {
        val mime = context.contentResolver.getType(uri) ?: mimeHint
        val extension = Attachments.extensionFor(mime)
            ?: throw ImageImportException("Only PNG, JPEG, GIF and WebP images can be added to notes.")
        val fileName = displayName(uri) ?: fallbackName
        val original = readAll(uri)
        val bytes = if (extension == "gif") original else process(original, extension)
        checkSize(bytes.size.toLong(), Attachments.MAX_BYTES)
        return ImportedImage(bytes, extension, fileName)
    }

    private fun readAll(uri: Uri): ByteArray {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw ImageImportException("Could not open the image.")
        return input.use {
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = it.read(buffer)
                if (read < 0) break
                out.write(buffer, 0, read)
                checkSize(out.size().toLong(), MAX_INPUT_BYTES)
            }
            out.toByteArray()
        }
    }

    private fun checkSize(size: Long, max: Long) {
        if (size > max) {
            throw ImageImportException(
                "This image is too large (%.1f MB). Images must be at most %d MB.".format(
                    size / BYTES_PER_MB,
                    Attachments.MAX_BYTES / (1024 * 1024)
                )
            )
        }
    }

    private fun displayName(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst() && !it.isNull(0)) it.getString(0) else null
        }
    } catch (e: SecurityException) {
        null
    } catch (e: IllegalArgumentException) {
        null
    }

    /** Downscales (and applies the JPEG orientation) only when needed, otherwise keeps the original bytes. */
    private fun process(bytes: ByteArray, extension: String): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw ImageImportException("Could not read the image.")
        val rotated = extension == "jpg" && hasRotation(bytes)
        if (Attachments.scaledSize(bounds.outWidth, bounds.outHeight) == null && !rotated) return bytes
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                Attachments.scaledSize(info.size.width, info.size.height)?.let { (w, h) -> decoder.setTargetSize(w, h) }
            }
        } catch (e: IOException) {
            throw ImageImportException("Could not read the image: ${e.message}")
        }
        return try {
            ByteArrayOutputStream().use { out ->
                bitmap.compress(formatFor(extension), if (extension == "png") 100 else QUALITY, out)
                out.toByteArray()
            }
        } finally {
            bitmap.recycle()
        }
    }

    // The framework ExifInterface is reliable on minSdk 29, no need for the androidx copy.
    @SuppressLint("ExifInterface")
    private fun hasRotation(bytes: ByteArray): Boolean = try {
        val orientation = ExifInterface(bytes.inputStream())
            .getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        orientation != ExifInterface.ORIENTATION_NORMAL && orientation != ExifInterface.ORIENTATION_UNDEFINED
    } catch (e: IOException) {
        false
    }

    @Suppress("DEPRECATION")
    private fun formatFor(extension: String): Bitmap.CompressFormat = when (extension) {
        "png" -> Bitmap.CompressFormat.PNG

        "webp" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }

        else -> Bitmap.CompressFormat.JPEG
    }

    private companion object {
        const val QUALITY = 90
        const val MAX_INPUT_BYTES = 64L * 1024 * 1024
        const val BUFFER_SIZE = 64 * 1024
        const val BYTES_PER_MB = 1024.0 * 1024.0
    }
}
