package com.tunombre.tvbridge

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Recibe el aviso del sistema cuando DownloadManager termina de bajar la
 * APK de una actualización (ver UpdateChecker.startDownload). */
class UpdateDownloadReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != DownloadManager.ACTION_DOWNLOAD_COMPLETE) return
        val downloadId = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
        if (downloadId == -1L) return
        UpdateChecker.onDownloadComplete(context, downloadId)
    }
}
