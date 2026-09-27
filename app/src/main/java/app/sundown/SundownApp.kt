package app.sundown

import android.app.Application
import android.content.Context
import app.sundown.data.SundownDb
import app.sundown.schedule.Notifier
import app.sundown.schedule.Scheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class SundownApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        Notifier.createChannels(this)
        // Re-arm on every start as well as on boot: a force-stop or a
        // reinstall drops every alarm and delivers no broadcast to say so.
        Graph.scope.launch { Scheduler.rearmAll(this@SundownApp) }
    }
}

/**
 * The app's few singletons. Small enough that a DI framework would be more
 * code than it saves.
 */
object Graph {
    lateinit var app: Context
        private set
    lateinit var db: SundownDb
        private set
    lateinit var prefs: Prefs
        private set

    /** Work that must outlive a screen or a broadcast: saving, logging, re-arming. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun init(context: Context) {
        app = context.applicationContext
        db = SundownDb.build(app)
        prefs = Prefs(app)
    }
}

/** User settings. Plain SharedPreferences mirrored into flows for Compose. */
class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("sundown", Context.MODE_PRIVATE)

    private val _cover = MutableStateFlow(sp.getBoolean(KEY_COVER, true))
    /** Draw a dark cover over the screen while Settings is being driven. */
    val cover: StateFlow<Boolean> = _cover
    fun setCover(v: Boolean) { sp.edit().putBoolean(KEY_COVER, v).apply(); _cover.value = v }

    private val _resultNotice = MutableStateFlow(sp.getBoolean(KEY_RESULT, true))
    /** Post a quiet notification after each run saying what was closed. */
    val resultNotice: StateFlow<Boolean> = _resultNotice
    fun setResultNotice(v: Boolean) { sp.edit().putBoolean(KEY_RESULT, v).apply(); _resultNotice.value = v }

    private companion object {
        const val KEY_COVER = "cover"
        const val KEY_RESULT = "result_notice"
    }
}
