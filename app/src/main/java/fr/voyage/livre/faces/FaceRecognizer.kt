package fr.voyage.livre.faces

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.tensorflow.lite.Interpreter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.max
import kotlin.math.sqrt

data class FoundFace(val thumbPath: String, val embedding: FloatArray)

/**
 * Repère les visages d'une photo (ML Kit) et calcule leur empreinte (modèle FaceNet).
 * Tout se passe sur le téléphone : aucune photo n'est envoyée sur Internet.
 * À utiliser hors du fil principal.
 */
class FaceRecognizer(context: Context, private val thumbsDir: File) : AutoCloseable {

    private val detector: FaceDetector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setMinFaceSize(0.04f)
            .build()
    )

    private val interpreter: Interpreter = context.assets.openFd(MODEL_FILE).use { fd ->
        val buffer = FileInputStream(fd.fileDescriptor).channel
            .map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
        Interpreter(buffer, Interpreter.Options().setNumThreads(4))
    }

    init {
        thumbsDir.mkdirs()
    }

    fun findFaces(photo: Bitmap): List<FoundFace> {
        val faces = Tasks.await(detector.process(InputImage.fromBitmap(photo, 0)))
        val minSide = max(MIN_FACE_PX, (max(photo.width, photo.height) * 0.03f).toInt())
        return faces
            .map { clamp(it.boundingBox, photo) }
            .filter { it.width() >= minSide && it.height() >= minSide }
            .map { box ->
                val crop = Bitmap.createBitmap(photo, box.left, box.top, box.width(), box.height())
                val embedding = embed(crop)
                val thumb = File(thumbsDir, "${java.util.UUID.randomUUID()}.jpg")
                FileOutputStream(thumb).use {
                    Bitmap.createScaledBitmap(crop, THUMB, THUMB, true).compress(Bitmap.CompressFormat.JPEG, 85, it)
                }
                FoundFace(thumb.absolutePath, embedding)
            }
            .filter { f -> f.embedding.none { it.isNaN() } }
    }

    /** Empreinte normalisée (longueur 1) : deux visages se comparent par simple produit scalaire. */
    private fun embed(face: Bitmap): FloatArray {
        val img = Bitmap.createScaledBitmap(face, INPUT, INPUT, true)
        val pixels = IntArray(INPUT * INPUT)
        img.getPixels(pixels, 0, INPUT, 0, 0, INPUT, INPUT)
        val values = FloatArray(INPUT * INPUT * 3)
        pixels.forEachIndexed { i, p ->
            values[3 * i] = ((p shr 16) and 0xFF).toFloat()
            values[3 * i + 1] = ((p shr 8) and 0xFF).toFloat()
            values[3 * i + 2] = (p and 0xFF).toFloat()
        }
        // Standardisation de l'image, comme à l'entraînement du modèle.
        val mean = values.average().toFloat()
        var std = sqrt(values.fold(0f) { acc, v -> acc + (v - mean) * (v - mean) } / values.size)
        std = max(std, 1f / sqrt(values.size.toFloat()))
        val input = ByteBuffer.allocateDirect(4 * values.size).order(ByteOrder.nativeOrder())
        values.forEach { input.putFloat((it - mean) / std) }
        input.rewind()

        val output = Array(1) { FloatArray(EMBEDDING) }
        interpreter.run(input, output)
        return normalize(output[0])
    }

    private fun clamp(r: Rect, bmp: Bitmap) = Rect(
        r.left.coerceIn(0, bmp.width - 1),
        r.top.coerceIn(0, bmp.height - 1),
        r.right.coerceIn(1, bmp.width),
        r.bottom.coerceIn(1, bmp.height),
    )

    override fun close() {
        detector.close()
        interpreter.close()
    }

    companion object {
        const val MODEL_FILE = "facenet.tflite"
        private const val INPUT = 160
        private const val EMBEDDING = 128
        private const val THUMB = 160
        private const val MIN_FACE_PX = 48

        fun normalize(v: FloatArray): FloatArray {
            val n = sqrt(v.fold(0f) { acc, x -> acc + x * x }).takeIf { it > 0f } ?: return v
            return FloatArray(v.size) { v[it] / n }
        }
    }
}
