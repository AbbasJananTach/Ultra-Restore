package com.example.data.local

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import com.example.domain.model.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

enum class ImportSourceType {
    LOCAL_GALLERY,
    GOOGLE_PHOTOS_CLOUD,
    GOOGLE_DRIVE_CLOUD,
}

data class ImportedMediaInfo(
    val sourcePath: String,
    val thumbnailPath: String,
    val displayName: String,
    val width: Int,
    val height: Int,
    val fps: Float,
    val durationMs: Long,
)

class MediaStorageHelper(private val context: Context) {

    private val projectsRootDir: File
        get() = File(context.filesDir, "projects").apply { mkdirs() }

    val exportsDir: File
        get() = File(context.filesDir, "exports").apply { mkdirs() }

    suspend fun importMediaToProject(
        projectId: String,
        uri: Uri,
        mediaType: MediaType,
        sourceType: ImportSourceType = ImportSourceType.LOCAL_GALLERY,
        onStatusUpdate: (String) -> Unit = {},
    ): ImportedMediaInfo = withContext(Dispatchers.IO) {
        val projectDir = File(projectsRootDir, projectId).apply { mkdirs() }
        val rawName = queryDisplayName(uri) ?: if (mediaType == MediaType.VIDEO) "Video_$projectId" else "Photo_$projectId"
        val cleanName = rawName.substringBeforeLast('.').ifBlank { rawName }
        val ext = when {
            rawName.contains('.') -> rawName.substringAfterLast('.').lowercase().take(5)
            mediaType == MediaType.VIDEO -> "mp4"
            else -> "jpg"
        }
        val thumbFile = File(projectDir, "thumb.jpg")

        val authority = uri.authority.orEmpty().lowercase()
        val isCloudUri = sourceType != ImportSourceType.LOCAL_GALLERY ||
            authority.contains("docs") ||
            authority.contains("drive") ||
            authority.contains("photos") ||
            authority.contains("cloud")

        if (mediaType == MediaType.PHOTO) {
            if (isCloudUri) {
                onStatusUpdate("Downloading Photo from Cloud...")
            } else {
                onStatusUpdate("Loading Photo into Studio...")
            }
            val sourceFile = File(projectDir, "source.$ext")
            context.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(sourceFile).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            } ?: throw IllegalStateException("Unable to read selected photo.")
            extractPhotoMetadataAndThumb(sourceFile, thumbFile, cleanName)
        } else {
            val persistableGranted = runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
                true
            }.getOrDefault(false)

            val canDirectSeek = if (!isCloudUri) {
                canExtractorSeekUriDirectly(uri)
            } else {
                false
            }
            val sizeBytes = queryFileSizeBytes(uri)
            val isLargeLocalVideo = !isCloudUri && sizeBytes > MAX_LOCAL_COPY_VIDEO_BYTES

            if (canDirectSeek && (persistableGranted || isLargeLocalVideo)) {
                onStatusUpdate("Analyzing Video Stream...")
                extractVideoMetadataAndThumbFromUri(uri, thumbFile, cleanName)
            } else {
                if (isCloudUri) {
                    onStatusUpdate("Downloading Video from Cloud...")
                } else {
                    onStatusUpdate("Preparing Video Workspace...")
                }
                val sourceFile = File(projectDir, "source.$ext")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(sourceFile).use { output ->
                        input.copyTo(output, bufferSize = 128 * 1024)
                    }
                } ?: throw IllegalStateException("Unable to download or read selected video.")
                onStatusUpdate("Extracting Video Specs...")
                extractVideoMetadataAndThumbFromFile(sourceFile, thumbFile, cleanName)
            }
        }
    }

    private fun canExtractorSeekUriDirectly(uri: Uri): Boolean {
        val extractor = MediaExtractor()
        return try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return false
            pfd.use {
                extractor.setDataSource(it.fileDescriptor)
                extractor.trackCount > 0
            }
        } catch (_: Throwable) {
            false
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun extractVideoMetadataAndThumbFromUri(
        uri: Uri,
        thumbFile: File,
        displayName: String,
    ): ImportedMediaInfo {
        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        var width = 1280
        var height = 720
        var durationMs = 0L
        var fps = 30f

        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
                val meta = parseRetrieverMetadata(retriever, thumbFile)
                width = meta.first
                height = meta.second
                durationMs = meta.third
                fps = meta.fourth
            } ?: run {
                retriever.setDataSource(context, uri)
                val meta = parseRetrieverMetadata(retriever, thumbFile)
                width = meta.first
                height = meta.second
                durationMs = meta.third
                fps = meta.fourth
            }
        } finally {
            runCatching { retriever.release() }
        }

        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
                fps = probeTrackFps(extractor, fps)
            }
        } catch (_: Throwable) {
        } finally {
            runCatching { extractor.release() }
        }

        return ImportedMediaInfo(
            sourcePath = uri.toString(),
            thumbnailPath = thumbFile.absolutePath,
            displayName = displayName,
            width = width,
            height = height,
            fps = fps,
            durationMs = durationMs,
        )
    }

    private fun extractVideoMetadataAndThumbFromFile(
        sourceFile: File,
        thumbFile: File,
        displayName: String,
    ): ImportedMediaInfo {
        val retriever = MediaMetadataRetriever()
        val extractor = MediaExtractor()
        var width = 1280
        var height = 720
        var durationMs = 0L
        var fps = 30f

        try {
            retriever.setDataSource(sourceFile.absolutePath)
            val meta = parseRetrieverMetadata(retriever, thumbFile)
            width = meta.first
            height = meta.second
            durationMs = meta.third
            fps = meta.fourth
        } finally {
            runCatching { retriever.release() }
        }

        try {
            extractor.setDataSource(sourceFile.absolutePath)
            fps = probeTrackFps(extractor, fps)
        } catch (_: Throwable) {
        } finally {
            runCatching { extractor.release() }
        }

        return ImportedMediaInfo(
            sourcePath = sourceFile.absolutePath,
            thumbnailPath = thumbFile.absolutePath,
            displayName = displayName,
            width = width,
            height = height,
            fps = fps,
            durationMs = durationMs,
        )
    }

    private data class Quad(val first: Int, val second: Int, val third: Long, val fourth: Float)

    private fun parseRetrieverMetadata(retriever: MediaMetadataRetriever, thumbFile: File): Quad {
        val rawW = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 1280
        val rawH = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 720
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        val captureRate = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
        val fps = if (captureRate != null && captureRate > 1f) captureRate else 30f

        val (width, height) = if (rotation == 90 || rotation == 270) rawH to rawW else rawW to rawH

        val frame = retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            ?: retriever.frameAtTime
        if (frame != null) {
            saveScaledThumb(frame, thumbFile)
            frame.recycle()
        }
        return Quad(width, height, durationMs, fps)
    }

    private fun probeTrackFps(extractor: MediaExtractor, fallbackFps: Float): Float {
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith("video/") && format.containsKey(MediaFormat.KEY_FRAME_RATE)) {
                val trackFps = format.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
                if (trackFps in 5f..240f) return trackFps
                break
            }
        }
        return fallbackFps
    }

    private fun extractPhotoMetadataAndThumb(
        sourceFile: File,
        thumbFile: File,
        displayName: String,
    ): ImportedMediaInfo {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(sourceFile.absolutePath, bounds)
        val rotation = readExifRotationDegrees(sourceFile.absolutePath)
        val rawW = bounds.outWidth.coerceAtLeast(64)
        val rawH = bounds.outHeight.coerceAtLeast(64)
        val (width, height) = if (rotation == 90 || rotation == 270) rawH to rawW else rawW to rawH

        val thumbBitmap = loadExifOrientedBitmap(sourceFile.absolutePath, maxDimension = 512)
        if (thumbBitmap != null) {
            saveScaledThumb(thumbBitmap, thumbFile)
            thumbBitmap.recycle()
        }

        return ImportedMediaInfo(
            sourcePath = sourceFile.absolutePath,
            thumbnailPath = thumbFile.absolutePath,
            displayName = displayName,
            width = width,
            height = height,
            fps = 0f,
            durationMs = 0L,
        )
    }

    private fun saveScaledThumb(bitmap: Bitmap, dest: File) {
        val maxSide = 480f
        val scale = (maxSide / max(bitmap.width, bitmap.height).toFloat()).coerceAtMost(1f)
        val targetW = (bitmap.width * scale).roundToInt().coerceAtLeast(1)
        val targetH = (bitmap.height * scale).roundToInt().coerceAtLeast(1)
        val scaled = if (scale < 0.99f) {
            Bitmap.createScaledBitmap(bitmap, targetW, targetH, true)
        } else {
            bitmap
        }
        FileOutputStream(dest).use { out ->
            scaled.compress(Bitmap.CompressFormat.JPEG, 88, out)
        }
        if (scaled !== bitmap) {
            scaled.recycle()
        }
    }

    suspend fun saveExportToGallery(
        exportedFile: File,
        mediaType: MediaType,
        displayName: String,
    ): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val isPng = exportedFile.extension.equals("png", ignoreCase = true)
        val mimeType = when (mediaType) {
            MediaType.VIDEO -> "video/mp4"
            MediaType.PHOTO -> if (isPng) "image/png" else "image/jpeg"
        }
        val safeTitle = displayName.replace(Regex("[^a-zA-Z0-9._-]"), "_").ifBlank { "UltraRestore" }
        val fileName = "${safeTitle}_UltraRestore.${exportedFile.extension}"

        val collection = if (mediaType == MediaType.VIDEO) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
        } else {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val relDir = if (mediaType == MediaType.VIDEO) {
                    "${Environment.DIRECTORY_MOVIES}/UltraRestore"
                } else {
                    "${Environment.DIRECTORY_PICTURES}/UltraRestore"
                }
                put(MediaStore.MediaColumns.RELATIVE_PATH, relDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
        }

        val itemUri = resolver.insert(collection, values)
            ?: throw IllegalStateException("Could not create MediaStore entry for Gallery save.")

        try {
            resolver.openOutputStream(itemUri)?.use { out ->
                FileInputStream(exportedFile).use { input ->
                    input.copyTo(out, bufferSize = 64 * 1024)
                }
            } ?: throw IllegalStateException("Could not open Gallery output stream.")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)
            }
        } catch (t: Throwable) {
            runCatching { resolver.delete(itemUri, null, null) }
            throw t
        }

        itemUri.toString()
    }

    fun createExportOutputFile(projectId: String, mediaType: MediaType, isPng: Boolean): File {
        val ext = when (mediaType) {
            MediaType.VIDEO -> "mp4"
            MediaType.PHOTO -> if (isPng) "png" else "jpg"
        }
        return File(exportsDir, "export_${projectId}_${System.currentTimeMillis()}.$ext")
    }

    fun getShareUri(file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    fun deleteProjectFiles(projectId: String, exportedPath: String?) {
        File(projectsRootDir, projectId).deleteRecursively()
        if (!exportedPath.isNullOrBlank()) {
            runCatching { File(exportedPath).delete() }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (idx >= 0) cursor.getString(idx) else null
                    } else null
                }
        }.getOrNull()
    }

    private fun queryFileSizeBytes(uri: Uri): Long {
        val colSize = runCatching {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val idx = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (idx >= 0 && !cursor.isNull(idx)) cursor.getLong(idx) else -1L
                    } else -1L
                }
        }.getOrNull() ?: -1L
        if (colSize > 0L) return colSize

        return runCatching {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
        }.getOrDefault(-1L)
    }

    companion object {
        private const val MAX_LOCAL_COPY_VIDEO_BYTES = 80L * 1024L * 1024L

        fun isContentUri(sourcePath: String): Boolean = sourcePath.startsWith("content://")

        fun readExifRotationDegrees(path: String): Int = runCatching {
            val exif = ExifInterface(path)
            when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
        }.getOrDefault(0)

        fun loadExifOrientedBitmap(path: String, maxDimension: Int): Bitmap? {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, bounds)
            val srcW = bounds.outWidth
            val srcH = bounds.outHeight
            if (srcW <= 0 || srcH <= 0) return null

            var sampleSize = 1
            while (max(srcW / sampleSize, srcH / sampleSize) > maxDimension * 2) {
                sampleSize *= 2
            }

            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = BitmapFactory.decodeFile(path, opts) ?: return null
            val rotation = readExifRotationDegrees(path)
            val maxCurrent = max(decoded.width, decoded.height)

            if (rotation == 0 && maxCurrent <= maxDimension) {
                return decoded
            }

            val matrix = Matrix()
            if (maxCurrent > maxDimension) {
                val scale = maxDimension.toFloat() / maxCurrent.toFloat()
                matrix.postScale(scale, scale)
            }
            if (rotation != 0) {
                matrix.postRotate(rotation.toFloat())
            }
            val transformed = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
            if (transformed !== decoded) {
                decoded.recycle()
            }
            return transformed
        }
    }
}
