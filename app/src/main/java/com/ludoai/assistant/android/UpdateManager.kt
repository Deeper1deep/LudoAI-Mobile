package com.ludoai.assistant.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.ludoai.assistant.BuildConfig
import com.ludoai.assistant.core.UpdateInfo
import com.ludoai.assistant.core.UpdateLogic
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

object UpdateManager {
    const val CHANNEL = "updates"

    sealed class Result {
        data class Available(val info: UpdateInfo) : Result()
        object UpToDate : Result()
        data class Error(val message: String) : Result()
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000; c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "LudoAI-Assistant/${BuildConfig.VERSION_NAME}")
        return c
    }

    /** Blocking. Call off the main thread. */
    fun check(ctx: Context): Result = try {
        val url = UpdateLogic.metadataUrl(BuildConfig.UPDATES_OWNER, BuildConfig.UPDATES_REPO)
        val text = open(url).inputStream.bufferedReader().use { it.readText() }
        val info = UpdateLogic.parse(text)
        if (info.applicationId.isNotEmpty() && info.applicationId != ctx.packageName.removeSuffix(".debug")) Result.Error("applicationId mismatch")
        else if (UpdateLogic.isNewer(BuildConfig.VERSION_CODE, info)) { Prefs(ctx).lastUpdateJson = text; Result.Available(info) }
        else Result.UpToDate
    } catch (e: Exception) { Result.Error(e.message ?: e.javaClass.simpleName) }

    fun notifyAvailable(ctx: Context, info: UpdateInfo) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT))
        val lang = Prefs(ctx).lang
        val pi = PendingIntent.getActivity(ctx, 1, Intent(ctx, MainActivity::class.java).putExtra("screen", "updates"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, CHANNEL).setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(S.get("newver", lang) + " " + info.versionName).setContentText(info.notes(lang).take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(info.notes(lang))).setContentIntent(pi).setAutoCancel(true).build()
        try { nm.notify(7, n) } catch (_: SecurityException) { }
    }

    /** Blocking; retries 3 times and verifies SHA-256 (and size). */
    fun download(ctx: Context, info: UpdateInfo, onProgress: (Long, Long) -> Unit): File? {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        val target = File(dir, "ludoai-update.apk")
        val ok = UpdateLogic.downloadWithRetry(3, { f ->
            val c = open(info.apkUrl); val total = if (info.sizeBytes > 0) info.sizeBytes else c.contentLengthLong
            c.inputStream.use { i -> f.outputStream().use { o ->
                val buf = ByteArray(64 * 1024); var done = 0L
                while (true) { val n = i.read(buf); if (n < 0) break; o.write(buf, 0, n); done += n; onProgress(done, total) }
            } }
        }, target, info)
        return if (ok) target else null
    }

    /** Opens the official Android package installer. Never bypasses the install-permission screen. */
    fun install(ctx: Context, apk: File): Boolean {
        if (!ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }

    fun schedulePeriodic(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        if (!Prefs(ctx).autoUpdateCheck) { js.cancel(42); return }
        if (js.allPendingJobs.any { it.id == 42 }) return
        js.schedule(JobInfo.Builder(42, ComponentName(ctx, UpdateJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(12 * 60 * 60 * 1000L).setPersisted(false).build())
    }
}

class UpdateJobService : JobService() {
    override fun onStartJob(params: JobParameters): Boolean {
        thread {
            val r = UpdateManager.check(applicationContext)
            if (r is UpdateManager.Result.Available) UpdateManager.notifyAvailable(applicationContext, r.info)
            jobFinished(params, false)
        }
        return true
    }
    override fun onStopJob(params: JobParameters) = true
}
