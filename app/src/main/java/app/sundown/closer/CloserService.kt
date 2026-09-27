package app.sundown.closer

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.LinearLayout
import android.widget.TextView
import app.sundown.Graph
import app.sundown.data.Apps
import app.sundown.data.LogEntry
import app.sundown.data.Outcome
import app.sundown.data.SeenScreen
import app.sundown.model.Target
import app.sundown.schedule.Notifier
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Closes apps the only way Android leaves open to a third-party app: by
 * pressing the same Force stop button a person would.
 *
 * `forceStopPackage` is signature-only, and `killBackgroundProcesses` cannot
 * touch an app that is on screen or holds a foreground service — which is
 * every app worth closing at night: the video that is playing, the music
 * player. So each app's App info page is opened, Force stop and its OK are
 * pressed through the accessibility tree, and Back returns to where the phone
 * was. A named screen is closed with Back instead, because stopping the app is
 * the only other way to remove it.
 *
 * Jobs run one at a time on the main thread. They are almost entirely waiting,
 * and the tree must be read on the thread the service's events arrive on.
 */
class CloserService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: CloserService? = null
            private set

        private val _connected = MutableStateFlow(false)
        /** Bound and able to act — distinct from merely switched on in Settings. */
        val connected: StateFlow<Boolean> = _connected
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val queue = Channel<CloseJob>(Channel.UNLIMITED)

    /** Jobs that arrived while the phone was locked, waiting for the next unlock. */
    private val waiting = mutableListOf<CloseJob>()

    /** The activity last seen on top, from window-state events. */
    private var topPackage: String? = null
    private var topActivity: String? = null

    private var cover: View? = null
    private val activityCache = HashMap<String, Boolean>()

    private val unlockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (waiting.isNotEmpty() && usable()) {
                waiting.forEach { queue.trySend(it) }
                waiting.clear()
            }
        }
    }

    override fun onServiceConnected() {
        instance = this
        _connected.value = true
        registerReceiver(
            unlockReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                // With no lock screen, USER_PRESENT may not follow SCREEN_ON.
                addAction(Intent.ACTION_SCREEN_ON)
            },
        )
        scope.launch { for (job in queue) run(job) }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        // Window-state events also come from dialogs, menus and the shade; only
        // a real activity of that package counts as "the screen on top".
        val key = "$pkg/$cls"
        val isActivity = activityCache.getOrPut(key) { Apps.isActivity(packageManager, pkg, cls) }
        if (!isActivity) return
        topPackage = pkg
        topActivity = cls
        if (pkg != packageName) {
            Graph.scope.launch { Graph.db.seen().put(SeenScreen(pkg, cls, System.currentTimeMillis())) }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        _connected.value = false
        runCatching { unregisterReceiver(unlockReceiver) }
        hideCover()
        scope.cancel()
        super.onDestroy()
    }

    fun enqueue(job: CloseJob) {
        queue.trySend(job)
    }

    // — a run —

    private fun usable(): Boolean {
        val power = getSystemService(PowerManager::class.java)
        val keyguard = getSystemService(KeyguardManager::class.java)
        return power.isInteractive && !keyguard.isKeyguardLocked
    }

    private suspend fun run(job: CloseJob) {
        if (job.pauseAudio) Closer.pauseAudio(this)

        // Behind the lock screen, Settings cannot be opened where we can reach
        // it. Do what works without it now, and finish on unlock — closing a
        // minute late is what was asked for; not closing at all is not.
        if (!usable()) {
            val entries = job.targets.map { t ->
                if (!t.isScreen) Closer.killBackground(this, t.packageName)
                Closer.entry(job, t, Outcome.Deferred, "Screen was off or locked; background processes stopped, the rest waits for unlock")
            }
            finish(job, entries)
            waiting += job
            return
        }

        val entries = mutableListOf<LogEntry>()
        // Screens first: whether one is open has to be judged on the phone as
        // the person left it, before Settings is opened over the top.
        job.targets.filter { it.isScreen }.forEach { entries += closeScreen(job, it) }

        val apps = job.targets.filter { !it.isScreen }.distinctBy { it.packageName }
        if (apps.isNotEmpty()) {
            if (apps.any { it.packageName == topPackage }) {
                performGlobalAction(GLOBAL_ACTION_HOME)
                delay(400)
            }
            if (Graph.prefs.cover.value) showCover(apps.joinToString { it.label })
            try {
                apps.forEach { entries += forceStop(job, it) }
            } finally {
                hideCover()
            }
        }
        finish(job, entries)
    }

    private suspend fun finish(job: CloseJob, entries: List<LogEntry>) {
        withContext(Dispatchers.IO) { Graph.db.log().insert(entries) }
        Notifier.result(this, job.name, entries)
    }

    private suspend fun closeScreen(job: CloseJob, t: Target): LogEntry {
        if (topPackage != t.packageName || topActivity != t.activity) {
            return Closer.entry(job, t, Outcome.ScreenNotOpen)
        }
        repeat(4) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            if (waitFor(1200) { topPackage != t.packageName || topActivity != t.activity }) {
                return Closer.entry(job, t, Outcome.ScreenClosed)
            }
        }
        // Some screens swallow Back (a player asking "exit?", a game). Home
        // always leaves.
        performGlobalAction(GLOBAL_ACTION_HOME)
        return Closer.entry(job, t, Outcome.ScreenClosed, "Back was ignored; went Home")
    }

    private suspend fun forceStop(job: CloseJob, t: Target): LogEntry {
        if (!Apps.isInstalled(this, t.packageName)) {
            return Closer.entry(job, t, Outcome.Failed, "Not installed")
        }
        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", t.packageName, null))
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_NO_HISTORY or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS,
            )
        val settingsPkg = intent.resolveActivity(packageManager)?.packageName
            ?: return fallback(job, t, "No App info screen on this phone")
        val strings = SettingsStrings.of(this, settingsPkg)
        val label = Apps.label(this, t.packageName)

        // An accessibility service is exempt from the background-activity-start
        // block, so this lands even though we are not on screen.
        runCatching { startActivity(intent) }.onFailure {
            return fallback(job, t, "Couldn't open App info: ${it.javaClass.simpleName}")
        }

        try {
            // Wait for the page of *this* app. A Settings task the person had
            // open would otherwise be on top for a moment showing something else.
            var button: AccessibilityNodeInfo? = null
            val loaded = waitFor(5000) {
                val roots = rootsOf(settingsPkg)
                val right = roots.any { hasText(it, label) }
                button = if (right) roots.firstNotNullOfOrNull { findButton(it, strings.forceStop) } else null
                button != null
            }
            if (!loaded) return fallback(job, t, "Couldn't find the \"${strings.forceStop}\" button")

            if (!isLive(button!!)) return Closer.entry(job, t, Outcome.NotRunning)

            clickable(button!!).performAction(AccessibilityNodeInfo.ACTION_CLICK)

            var ok: AccessibilityNodeInfo? = null
            val confirmed = waitFor(3000) {
                ok = rootsOf(settingsPkg).firstNotNullOfOrNull { findOk(it, strings.ok) }
                ok != null
            }
            if (!confirmed) return fallback(job, t, "The confirmation dialog didn't appear")
            clickable(ok!!).performAction(AccessibilityNodeInfo.ACTION_CLICK)

            // The button greys out once the app is stopped. An app that
            // restarts itself at once (a persistent service) leaves it enabled;
            // it was still stopped, and the detail says what was seen.
            val greyed = waitFor(2500) {
                rootsOf(settingsPkg).firstNotNullOfOrNull { findButton(it, strings.forceStop) }?.let { !isLive(it) } == true
            }
            return Closer.entry(
                job, t, Outcome.ForceStopped,
                if (greyed) "" else "Stopped; Android restarted a background service of it straight away",
            )
        } finally {
            leaveSettings(settingsPkg)
        }
    }

    private fun fallback(job: CloseJob, t: Target, why: String): LogEntry {
        Closer.killBackground(this, t.packageName)
        return Closer.entry(job, t, Outcome.BackgroundKilled, why)
    }

    /** Back out of the App info page we opened, back to what was there before. */
    private suspend fun leaveSettings(settingsPkg: String) {
        repeat(3) {
            if (rootInActiveWindow?.packageName?.toString() != settingsPkg) return
            performGlobalAction(GLOBAL_ACTION_BACK)
            waitFor(800) { rootInActiveWindow?.packageName?.toString() != settingsPkg }
        }
    }

    // — reading the tree —

    /**
     * Every window root belonging to [pkg]. `rootInActiveWindow` alone is not
     * enough: the confirmation is a separate dialog window, and our own cover
     * may be the topmost window on screen.
     */
    private fun rootsOf(pkg: String): List<AccessibilityNodeInfo> =
        windows.mapNotNull { it.root }.filter { it.packageName?.toString() == pkg }
            .ifEmpty { listOfNotNull(rootInActiveWindow?.takeIf { it.packageName?.toString() == pkg }) }

    /**
     * Depth-first search of the live tree.
     *
     * Not `findAccessibilityNodeInfosByText`: on Android 16's App info page it
     * returns nothing at all while a walk of the same root finds "Force stop"
     * plainly, so the platform search cannot be trusted with the one lookup
     * everything here depends on.
     */
    private fun find(root: AccessibilityNodeInfo?, depth: Int = 0, match: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (root == null || depth > 60) return null
        if (match(root)) return root
        for (i in 0 until root.childCount) {
            find(root.getChild(i), depth + 1, match)?.let { return it }
        }
        return null
    }

    private fun AccessibilityNodeInfo.says(text: String): Boolean =
        this.text?.toString()?.trim().equals(text, ignoreCase = true) ||
            contentDescription?.toString()?.trim().equals(text, ignoreCase = true)

    private fun hasText(root: AccessibilityNodeInfo, text: String): Boolean = find(root) { it.says(text) } != null

    private fun findButton(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? = find(root) { it.says(text) }

    /** The dialog's positive button: by its framework id first, its label second. */
    private fun findOk(root: AccessibilityNodeInfo, ok: String): AccessibilityNodeInfo? =
        find(root) { it.viewIdResourceName == "android:id/button1" } ?: findButton(root, ok)

    /**
     * Whether a button can be pressed. On App info the label is a TextView
     * inside the view that takes the click, and it is that view — not the
     * label — which Settings disables when the app is already stopped.
     */
    private fun isLive(node: AccessibilityNodeInfo): Boolean = node.isEnabled && clickable(node).isEnabled

    /** Text is often a child of the view that takes the click. */
    private fun clickable(node: AccessibilityNodeInfo): AccessibilityNodeInfo {
        var n: AccessibilityNodeInfo? = node
        while (n != null && !n.isClickable) n = n.parent
        return n ?: node
    }

    private suspend fun waitFor(timeoutMs: Long, check: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (runCatching(check).getOrDefault(false)) return true
            if (System.currentTimeMillis() > end) return false
            delay(100)
        }
    }

    // — the cover —

    /**
     * A dark sheet over the screen while Settings is being driven, so a run
     * reads as one deliberate thing instead of pages flickering past. It is an
     * accessibility overlay, which does not take focus and so does not hide
     * the Settings windows from the tree; the clicks are delivered as actions,
     * not touches, so it cannot intercept them either.
     */
    private fun showCover(names: String) {
        if (cover != null) return
        val dp = { v: Float -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics).toInt() }
        val view = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xF2161826.toInt())
            setPadding(dp(32f), 0, dp(32f), 0)
            addView(TextView(context).apply {
                text = "SUNDOWN"
                letterSpacing = 0.1f
                textSize = 10f
                setTextColor(0xFF9397AB.toInt())
                typeface = android.graphics.Typeface.MONOSPACE
                gravity = Gravity.CENTER
            })
            addView(TextView(context).apply {
                text = "Closing $names"
                textSize = 19f
                setTextColor(0xFFE9E9ED.toInt())
                gravity = Gravity.CENTER
                setPadding(0, dp(10f), 0, 0)
            })
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        runCatching {
            getSystemService(WindowManager::class.java).addView(view, params)
            cover = view
        }
    }

    private fun hideCover() {
        val v = cover ?: return
        cover = null
        runCatching { getSystemService(WindowManager::class.java).removeView(v) }
    }
}

/**
 * The words on the Settings buttons, in the phone's language, read from the
 * Settings app itself rather than hardcoded — "Force stop" is "Forzar
 * detención" on a Spanish phone and something else again on an OEM skin.
 */
private data class SettingsStrings(val forceStop: String, val ok: String) {
    companion object {
        fun of(context: Context, settingsPkg: String): SettingsStrings {
            val res = runCatching { context.packageManager.getResourcesForApplication(settingsPkg) }.getOrNull()
            fun str(name: String): String? = res?.let { r ->
                val id = r.getIdentifier(name, "string", settingsPkg)
                if (id != 0) runCatching { r.getString(id) }.getOrNull() else null
            }
            return SettingsStrings(
                forceStop = str("force_stop") ?: "Force stop",
                ok = str("dlg_ok") ?: context.getString(android.R.string.ok),
            )
        }
    }
}
