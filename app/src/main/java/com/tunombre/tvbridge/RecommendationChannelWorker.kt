package com.tunombre.tvbridge

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

/** Ejecutado en segundo plano para refrescar la fila de "Recomendado para
 * ti" (ver RecommendationChannelManager.refresh y
 * RecommendationScheduler para cuándo se dispara). */
class RecommendationChannelWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        RecommendationChannelManager.refresh(applicationContext)
        return Result.success()
    }
}
