package com.tunombre.tvbridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log

/**
 * Redirige un vídeo de YouTube recomendado a TizenTube Cobalt — un cliente
 * de YouTube sin anuncios para Android TV (https://github.com/reisxd/TizenTubeCobalt),
 * pese al nombre no es exclusivo de Tizen.
 *
 * TizenTube Cobalt en sí no expone un intent-filter propio para enlaces de
 * youtube.com — para eso hace falta instalar además "TizenTube Bridge"
 * (https://github.com/TobiPeterG/tizentube-bridge), que se registra con el
 * mismo ID de paquete que la app oficial de YouTube TV
 * (com.google.android.youtube.tv, sustituyéndola — no pueden convivir las
 * dos) y reenvía a Cobalt lo que reciba, incluidos enlaces
 * youtube.com/results?search_query=. Por eso apuntamos a ese paquete y no
 * al de Cobalt directamente.
 *
 * Solo se usa si el usuario ha elegido explícitamente TizenTube Cobalt como
 * destino de YouTube (ver Preferences.YoutubeApp) — si alguien no tiene el
 * bridge instalado y conserva la app oficial de YouTube, apuntar a este
 * paquete simplemente abriría la YouTube TV normal, así que no tiene
 * sentido intentarlo como se hace con SmartTube.
 */
object TizenTubeLauncher {

    private const val TAG = "TizenTubeLauncher"
    private const val BRIDGE_PACKAGE = "com.google.android.youtube.tv"

    fun openSearch(service: Context, title: String) {
        val uri = Uri.parse("https://www.youtube.com/results?search_query=${Uri.encode(title)}")
        val intent = Intent(Intent.ACTION_VIEW).apply {
            data = uri
            setPackage(BRIDGE_PACKAGE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        try {
            service.startActivity(intent)
            Log.d(TAG, "Abriendo TizenTube Cobalt (vía bridge): $uri")
        } catch (e: Exception) {
            Log.d(TAG, "TizenTube Bridge no disponible, se deja el comportamiento normal", e)
        }
    }
}
