package com.stickmanai.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Crash visibility net ("debug en vivo sin adb"): instala un default uncaught exception handler
 * que, en vez de matar la app en silencio, escribe el stack en filesDir/crash.log y muestra una
 * notificacion del sistema con el error. Asi cualquier crash del arranque ("Iniciar") se puede
 * leer en la pantalla misma y reportarlo. `report()` es la version "no fatal": para los guardas
 * que evitan que un fallo puntual tire el servicio, pero igual lo quieren ver en pantalla.
 */
object CrashReporter {
    private const val TAG = "StickmanAI"
    private const val CHANNEL_ID = "crash"
    private const val NOTIF_ID = 0xCA4A

    private val installed = AtomicBoolean(false)

    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val text = stackText(throwable, maxFrames = 15)
                writeLog(context, text)
                notify(context, "Error al iniciar/ejecutar: ${throwable.javaClass.simpleName}", text)
                Log.e(TAG, "uncaught en ${thread.name}", throwable)
            } catch (_: Throwable) {
                // nunca fallar dentro del handler
            }
            // No se re-lanza a propósito: se muestra la notificacion y el proceso queda vivo para
            // que el usuario pueda leer el error en pantalla. (Si esto provoca un estado inconsistente,
            // el usuario cierra la app normalmente como haría con cualquier crash.)
        }
        ensureChannel(context)
    }

    /** Guarda (no fatal) - usa esto en los try/catch que protegen el arranque. */
    fun report(context: Context, where: String, throwable: Throwable) {
        try {
            val text = stackText(throwable, maxFrames = 12)
            writeLog(context, text)
            notify(context, "Fallo en $where: ${throwable.javaClass.simpleName}", text)
            Log.e(TAG, "guard capturado en $where", throwable)
        } catch (_: Throwable) {}
    }

    private fun stackText(throwable: Throwable, maxFrames: Int): String = buildString {
        append(throwable.toString()).append('\n')
        throwable.stackTrace.take(maxFrames).forEach { append("    at ").append(it).append('\n') }
    }

    private fun writeLog(context: Context, text: String) {
        try {
            val stamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            File(context.filesDir, "crash.log").appendText("\n=== $stamp ===\n$text\n")
        } catch (_: Throwable) {}
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            try {
                val nm = context.getSystemService(NotificationManager::class.java)
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Errores", NotificationManager.IMPORTANCE_HIGH))
            } catch (_: Throwable) {}
        }
    }

    @Suppress("DEPRECATION")
    private fun notify(context: Context, title: String, text: String) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            Notification.Builder(context)
        }
        val notif = builder
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(title)
            .setContentText(text.lineSequence().firstOrNull()?.take(90) ?: text.take(90))
            .setStyle(Notification.BigTextStyle().bigText(text.take(1600)))
            .setAutoCancel(false)
            .build()
        nm.notify(NOTIF_ID, notif)
    }
}