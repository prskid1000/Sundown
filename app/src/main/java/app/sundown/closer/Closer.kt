package app.sundown.closer

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import app.sundown.Graph
import app.sundown.data.LogEntry
import app.sundown.data.Outcome
import app.sundown.model.Target
import app.sundown.schedule.Notifier
import kotlinx.coroutines.launch

/** One run: the targets of one rule at one moment. */
data class CloseJob(
    val name: String,
    val targets: List<Target>,
    val pauseAudio: Boolean,
    val runId: Long = System.currentTimeMillis(),
)

/**
 * The front door to closing. Hands the job to [CloserService] when it is on,
 * and otherwise does the little that is possible without it — and says so.
 */
object Closer {

    fun submit(context: Context, name: String, targets: List<Target>, pauseAudio: Boolean) {
        if (targets.isEmpty()) return
        val job = CloseJob(name, targets, pauseAudio)
        val service = CloserService.instance
        if (service != null) {
            service.enqueue(job)
            return
        }

        // No service: nothing can press Force stop, and nothing can see which
        // screen is on top. Kill what is in the background, and report that
        // this is all that happened rather than calling it closed.
        if (pauseAudio) pauseAudio(context)
        val entries = targets.map { t ->
            if (t.isScreen) {
                entry(job, t, Outcome.Failed, "Accessibility service is off")
            } else {
                killBackground(context, t.packageName)
                entry(job, t, Outcome.BackgroundKilled, "Accessibility service is off")
            }
        }
        Graph.scope.launch { Graph.db.log().insert(entries) }
        Notifier.accessibilityOff(context, targets.joinToString { it.screenName ?: it.label })
    }

    fun killBackground(context: Context, pkg: String) {
        runCatching { context.getSystemService(ActivityManager::class.java).killBackgroundProcesses(pkg) }
    }

    /**
     * Take audio focus for a moment. A well-behaved player pauses on a
     * permanent loss, so audio stops cleanly before its app is stopped rather
     * than being cut off mid-word. Which app is playing is not visible to us
     * without a notification listener, so this pauses whatever is playing.
     */
    fun pauseAudio(context: Context) {
        val am = context.getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setOnAudioFocusChangeListener { }
            .build()
        am.requestAudioFocus(request)
        Handler(Looper.getMainLooper()).postDelayed({ am.abandonAudioFocusRequest(request) }, 1500)
    }

    fun entry(job: CloseJob, t: Target, outcome: Outcome, detail: String = "") = LogEntry(
        at = System.currentTimeMillis(),
        runId = job.runId,
        ruleName = job.name,
        packageName = t.packageName,
        label = t.label,
        activity = t.activity,
        outcome = outcome,
        detail = detail,
    )

    /**
     * Whether the service is switched on in Settings. [CloserService.instance]
     * says whether it is *bound*, which lags the toggle; the setting is the
     * truth the user can see.
     */
    fun isEnabled(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(context, CloserService::class.java)
        val splitter = TextUtils.SimpleStringSplitter(':').apply { setString(enabled) }
        return splitter.any { ComponentName.unflattenFromString(it) == me }
    }
}
