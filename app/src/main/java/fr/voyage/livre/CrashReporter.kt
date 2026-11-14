package fr.voyage.livre

import android.app.Application
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Démarre le rapport de plantage dès le lancement de l'application. */
class LivreApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}

/**
 * Si l'application se ferme sur une erreur, le détail est enregistré ; il est affiché au
 * lancement suivant pour pouvoir être copié et transmis.
 */
object CrashReporter {

    private fun file(context: Context) = File(context.filesDir, "dernier-plantage.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(app, error, "Fermeture de l'application (fil ${thread.name})") }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Enregistre une erreur (fatale ou non) dans le rapport. */
    fun record(context: Context, error: Throwable, what: String) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
        val version = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull()
        val text = buildString {
            appendLine("Livre de voyage $version – Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) – ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine(SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.FRANCE).format(Date()) + " – " + what)
            appendLine(trace.take(6000))
        }
        file(context).writeText(text)
    }

    fun lastReport(context: Context): String? = file(context).takeIf { it.exists() }?.readText()

    fun clear(context: Context) {
        file(context).delete()
    }
}
