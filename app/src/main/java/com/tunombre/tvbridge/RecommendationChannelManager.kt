package com.tunombre.tvbridge

import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.tvprovider.media.tv.Channel
import androidx.tvprovider.media.tv.ChannelLogoUtils
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Publica y refresca nuestra propia fila de "Recomendado para ti" en la
 * pantalla de inicio de Android TV/Google TV, usando la API oficial de
 * canales (ver RecommendationChannelWorker, que llama a esto en segundo
 * plano). Solo Google TV: Fire OS no tiene este sistema de canales de
 * inicio, así que en Fire TV esto simplemente no hace nada útil (no hay
 * pantalla de inicio de Android TV que lo muestre).
 *
 * Best-effort: en la práctica, muchos launchers de Google TV (comprobado en
 * un TCL con launcherx) cancelan silenciosamente la aprobación del canal
 * (`ACTION_REQUEST_CHANNEL_BROWSABLE` vuelve con RESULT_CANCELED sin mostrar
 * ningún diálogo), así que la fila puede no llegar a verse nunca en el home
 * real aunque el canal se cree y se rellene correctamente. Por eso la vía
 * garantizada es [RecommendationsActivity], una fila equivalente dentro de
 * la propia app — esta clase se deja activa por si el launcher del usuario
 * sí lo soporta (p.ej. Android TV AOSP de serie).
 *
 * Las recomendaciones en sí salen de TMDb (ver [RecommendationEngine]) —
 * nada de LLM, así que no hay riesgo de que "recomiende" una película que
 * no existe.
 */
object RecommendationChannelManager {
    private const val TAG = "RecChannel"
    private const val PREFS_NAME = "tvbridge_prefs"
    private const val KEY_CHANNEL_ID = "recommendation_channel_id"
    private const val MAX_PROGRAMS = 15

    private const val WORK_NAME_PERIODIC = "recommendation_channel_refresh"
    private const val WORK_NAME_ONE_SHOT = "recommendation_channel_refresh_now"

    /** Refresco puntual — al activar la función desde Ajustes, o justo
     * después de abrir un título nuevo, para que la fila no tarde días en
     * reflejar lo último visto. Reemplaza cualquier refresco puntual que
     * siguiera pendiente (no tiene sentido apilarlos). */
    fun scheduleOneShotRefresh(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = OneTimeWorkRequestBuilder<RecommendationChannelWorker>()
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK_NAME_ONE_SHOT,
            ExistingWorkPolicy.REPLACE,
            request
        )
    }

    /** Red de seguridad periódica (una vez al día) para que la fila no se
     * quede parada si el usuario no vuelve a abrir nada durante un tiempo —
     * las recomendaciones de TMDb en sí sí pueden cambiar con el tiempo
     * aunque el historial no lo haga. Llamar una vez, p.ej. desde
     * MainActivity.onCreate — KEEP hace que llamarlo varias veces no
     * reprograme nada. */
    fun schedulePeriodicRefresh(context: Context) {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<RecommendationChannelWorker>(1, TimeUnit.DAYS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            WORK_NAME_PERIODIC,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    fun getChannelId(context: Context): Long? {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong(KEY_CHANNEL_ID, -1L)
        return stored.takeIf { it != -1L }
    }

    private fun saveChannelId(context: Context, channelId: Long) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_CHANNEL_ID, channelId)
            .apply()
    }

    /** Crea el canal si hace falta (una sola vez) y lo pide visible en la
     * pantalla de inicio. Idempotente: si ya existe, no hace nada. */
    private fun ensureChannel(context: Context): Long? {
        getChannelId(context)?.let { return it }

        return try {
            val appLinkIntent = Intent(context, MainActivity::class.java)
            val builder = Channel.Builder()
                .setType(TvContractCompat.Channels.TYPE_PREVIEW)
                .setDisplayName(context.getString(R.string.recommendation_channel_name))
                .setAppLinkIntentUri(Uri.parse(appLinkIntent.toUri(Intent.URI_INTENT_SCHEME)))

            val channelUri = context.contentResolver.insert(
                TvContractCompat.Channels.CONTENT_URI,
                builder.build().toContentValues()
            ) ?: return null
            val channelId = ContentUris.parseId(channelUri)

            val logo = BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
            if (logo != null) {
                ChannelLogoUtils.storeChannelLogo(context, channelId, logo)
            }

            // El primer canal que crea la app se marca visible directamente
            // — es justo nuestro único canal, así que no hace falta pedirle
            // permiso aparte al usuario (ver requestBrowsable más abajo para
            // el caso de que lo haya ocultado él mismo luego).
            TvContractCompat.requestChannelBrowsable(context, channelId)

            saveChannelId(context, channelId)
            Log.d(TAG, "Canal de recomendaciones creado: $channelId")
            logChannelState(context, channelId)
            channelId
        } catch (e: Exception) {
            Log.e(TAG, "Error creando el canal de recomendaciones", e)
            null
        }
    }

    /** Diagnóstico: vuelve a leer el canal desde el TvProvider (no nos
     * fiamos de lo que insertamos, sino de lo que el sistema diga que
     * quedó guardado) y registra su estado real — para saber si el
     * launcher lo está tratando como visible/predeterminado o no. */
    private fun logChannelState(context: Context, channelId: Long) {
        try {
            context.contentResolver.query(
                TvContractCompat.buildChannelUri(channelId), null, null, null, null
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val channel = Channel.fromCursor(cursor)
                    Log.d(
                        TAG,
                        "Estado canal $channelId: browsable=${channel.isBrowsable} " +
                            "searchable=${channel.isSearchable} type=${channel.type} " +
                            "displayName=${channel.displayName}"
                    )
                } else {
                    Log.w(TAG, "Estado canal $channelId: el TvProvider no devolvió ninguna fila para este ID")
                }
            } ?: Log.w(TAG, "Estado canal $channelId: query() devolvió null (¿proveedor no disponible?)")

            context.contentResolver.query(
                TvContractCompat.buildPreviewProgramsUriForChannel(channelId), null, null, null, null
            )?.use { cursor ->
                Log.d(TAG, "Programas publicados en canal $channelId: ${cursor.count}")
            }

            // Diagnóstico: ¿tenemos canales huérfanos de instalaciones
            // anteriores? El sistema decide "canal predeterminado" mirando
            // el histórico de canales del paquete, no solo el actual — si
            // hay más de uno, puede que el sistema ya no considere a este
            // el "primer" canal aunque sea el único que usamos nosotros.
            context.contentResolver.query(
                TvContractCompat.Channels.CONTENT_URI, null, null, null, null
            )?.use { cursor ->
                val idIdx = cursor.getColumnIndex(TvContractCompat.Channels._ID)
                val pkgIdx = cursor.getColumnIndex(TvContractCompat.Channels.COLUMN_PACKAGE_NAME)
                val browsableIdx = cursor.getColumnIndex(TvContractCompat.Channels.COLUMN_BROWSABLE)
                val ours = mutableListOf<String>()
                while (cursor.moveToNext()) {
                    val pkg = if (pkgIdx >= 0) cursor.getString(pkgIdx) else null
                    if (pkg == context.packageName) {
                        val id = if (idIdx >= 0) cursor.getLong(idIdx) else -1L
                        val browsable = if (browsableIdx >= 0) cursor.getInt(browsableIdx) else -1
                        ours.add("$id(browsable=$browsable)")
                    }
                }
                Log.d(TAG, "Canales totales de ${context.packageName} en el TvProvider: ${ours.size} -> $ours")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error consultando el estado del canal", e)
        }
    }

    /** Vuelve a pedir que el canal sea visible — para cuando el usuario lo
     * quitó de la pantalla de inicio y quiere recuperarlo desde Ajustes
     * (ver MainMenuStepFragment). Sin efecto si ya es visible. */
    fun requestBrowsable(context: Context): Intent? {
        val channelId = ensureChannel(context) ?: return null
        return Intent(TvContractCompat.ACTION_REQUEST_CHANNEL_BROWSABLE).apply {
            putExtra(TvContractCompat.EXTRA_CHANNEL_ID, channelId)
        }
    }

    /** Vuelve a calcular las recomendaciones a partir del historial y
     * reemplaza todo el contenido del canal. Bloqueante (llamadas de red a
     * TMDb) — llamar siempre desde un hilo de fondo (ver
     * RecommendationChannelWorker). No hace nada si todavía no hay
     * historial (usuario nuevo, o que nunca ha abierto nada con la app). */
    fun refresh(context: Context) {
        val recommendations = RecommendationEngine.compute(context)
        if (recommendations.isEmpty) {
            Log.d(TAG, "Sin historial todavía — no se publica nada")
            return
        }
        // Todas las filas "Porque viste X" mezcladas en una sola, y con un
        // límite propio más bajo — una fila de la pantalla de inicio de
        // Android TV no necesita el mismo detalle por semilla que la
        // pantalla dedicada dentro de la app, ni distinguir de qué título
        // vino cada recomendación.
        val ordered = (recommendations.movieRows + recommendations.seriesRows)
            .flatMap { it.recommendations }
            .distinctBy { it.tmdbId to it.mediaPath }
            .sortedByDescending { it.popularity }
            .take(MAX_PROGRAMS)
        val channelId = ensureChannel(context) ?: return

        try {
            // Se reemplaza todo el contenido en cada refresco en vez de
            // calcular una diferencia — mucho más simple, y evita tener que
            // llevar la cuenta de qué "programa" concreto corresponde a cada
            // recomendación entre refrescos.
            context.contentResolver.delete(
                TvContractCompat.buildPreviewProgramsUriForChannel(channelId),
                null,
                null
            )

            for (rec in ordered) {
                val posterUri = rec.posterPath?.let { Uri.parse("https://image.tmdb.org/t/p/w500$it") }
                val clickIntentUri = "tvbridge://recommend?tmdbId=${rec.tmdbId}&mediaPath=${rec.mediaPath}" +
                    "&title=${Uri.encode(rec.title)}"

                val programBuilder = PreviewProgram.Builder()
                    .setChannelId(channelId)
                    .setType(
                        if (rec.type == MediaType.SERIES) TvContractCompat.PreviewPrograms.TYPE_TV_SERIES
                        else TvContractCompat.PreviewPrograms.TYPE_MOVIE
                    )
                    .setTitle(rec.title)
                    .setIntentUri(Uri.parse(clickIntentUri))
                    .setInternalProviderId("${rec.mediaPath}_${rec.tmdbId}")
                if (posterUri != null) {
                    programBuilder.setPosterArtUri(posterUri)
                }

                context.contentResolver.insert(
                    TvContractCompat.PreviewPrograms.CONTENT_URI,
                    programBuilder.build().toContentValues()
                )
            }
            Log.d(TAG, "Fila de recomendaciones actualizada: ${ordered.size} títulos")
            logChannelState(context, channelId)
        } catch (e: Exception) {
            Log.e(TAG, "Error publicando las recomendaciones", e)
        }
    }
}
