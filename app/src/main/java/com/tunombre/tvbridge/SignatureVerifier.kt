package com.tunombre.tvbridge

import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import android.util.Log
import java.security.MessageDigest

/**
 * Comprueba que el APK en ejecución está firmado con la clave de release
 * real, no con una clave distinta — el caso típico es alguien que
 * decompila, quita el chequeo de LicenseManager.isLikelyValid, y vuelve a
 * firmar el APK parcheado con su propia clave para poder instalarlo (un
 * APK no se puede reinstalar/actualizar con una firma distinta a la
 * original, pero si es una instalación nueva, sí). No es infalible —
 * quien llegue a localizar y quitar ESTA comprobación también puede
 * quitar cualquier otra — pero sí rompe el ataque más accesible
 * ("parchear y recompilar con una clave cualquiera").
 *
 * Solo tiene sentido aplicarlo en builds de release: en debug la firma es
 * la del keystore de depuración de cada máquina, así que nunca
 * coincidiría con la huella de abajo — ver el `if (!BuildConfig.DEBUG...)`
 * en el punto de uso (TvRecommendationAccessibilityService).
 */
object SignatureVerifier {

    private const val TAG = "SignatureVerifier"

    // SHA-256 del certificado de release real (keystore/tvbridge-release.jks,
    // alias "tvbridge"). Obtenida con:
    // keytool -list -v -keystore keystore/tvbridge-release.jks -alias tvbridge
    // Si algún día se rota la clave de firma, esto hay que actualizarlo a
    // la vez (y entonces sí que las instalaciones existentes no podrán
    // actualizarse in-place, como con cualquier cambio de clave).
    private const val EXPECTED_SHA256 =
        "14EA2687D5182663BD8589C8933A0F5897D64BDA52519E36CF3AAA9E5112AEA1"

    /** true si la firma actual NO coincide con la esperada — o si por
     * cualquier motivo no se ha podido comprobar (mejor bloquear que dar
     * por buena una firma que no se ha podido verificar). */
    fun isTampered(context: Context): Boolean {
        val actual = currentSignatureSha256(context) ?: return true
        return !actual.equals(EXPECTED_SHA256, ignoreCase = true)
    }

    private fun currentSignatureSha256(context: Context): String? {
        return try {
            val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                val signingInfo = info.signingInfo ?: return null
                // Esta app nunca ha rotado su firma, así que la única
                // certificación válida es la de origen — si hubiera más de
                // una (firma rotada) o viniera de apkContentsSigners
                // (firma con clave nueva tras una rotación), no es el caso
                // esperado y se trata como no verificable.
                if (signingInfo.hasMultipleSigners()) null
                else signingInfo.signingCertificateHistory?.firstOrNull()
            } else {
                @Suppress("DEPRECATION")
                val info = context.packageManager.getPackageInfo(
                    context.packageName,
                    PackageManager.GET_SIGNATURES
                )
                @Suppress("DEPRECATION")
                info.signatures?.firstOrNull()
            }
            signature?.let { sha256Hex(it) }
        } catch (e: Exception) {
            Log.e(TAG, "Error comprobando la firma del APK", e)
            null
        }
    }

    private fun sha256Hex(signature: Signature): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
        return digest.joinToString("") { "%02X".format(it) }
    }
}
