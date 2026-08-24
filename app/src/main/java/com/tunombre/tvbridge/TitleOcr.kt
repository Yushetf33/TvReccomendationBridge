package com.tunombre.tvbridge

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

/**
 * OCR on-device (ML Kit, variante "bundled" — sin Google Play Services,
 * necesario porque Fire TV no lo tiene) para leer el título de la
 * recomendación seleccionada en el modo de captura de pantalla de Fire TV
 * (ver [FireTvCaptureService]).
 */
object TitleOcr {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /**
     * Bloqueante — llamar siempre desde un hilo de fondo. Devuelve la
     * primera línea de texto reconocida (donde vive el título, según el
     * recorte que le pasemos), o null si no se reconoce nada.
     */
    fun recognizeFirstLine(bitmap: Bitmap): String? {
        val image = InputImage.fromBitmap(bitmap, 0)
        val result = Tasks.await(recognizer.process(image))
        return result.textBlocks
            .flatMap { it.lines }
            .firstOrNull { it.text.isNotBlank() }
            ?.text
    }
}
