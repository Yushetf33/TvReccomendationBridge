package com.tunombre.tvbridge

import android.util.Base64

/**
 * Guarda como literales las cadenas más "copiables" del proyecto (los
 * marcadores de detección de tarjetas del launcher — ver
 * TvRecommendationAccessibilityService.TITLE_MARKERS/YOUTUBE_DURATION_MARKERS)
 * codificadas en vez de en texto plano.
 *
 * Motivo: R8/minify (ya activo en release) solo ofusca nombres de
 * clases/métodos, nunca literales de texto — un `strings app-release.apk` o
 * un decompilador (jadx) los deja tal cual. Confirmado que un proyecto de
 * terceros (TVRelay) copió estos marcadores exactos leyéndolos así, sin
 * tocar nuestro repo (privado desde siempre). Esto no es infranqueable
 * (cualquier APK es descompilable y el propio decode() es legible), pero
 * evita el copy-paste trivial de un `strings`/grep directo.
 */
internal object Obfuscated {
    private const val KEY = 0x5A

    fun decode(encoded: String): String {
        val bytes = Base64.decode(encoded, Base64.NO_WRAP)
        val decoded = ByteArray(bytes.size) { i -> (bytes[i].toInt() xor KEY).toByte() }
        return String(decoded, Charsets.UTF_8)
    }
}
