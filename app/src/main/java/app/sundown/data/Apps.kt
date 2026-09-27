package app.sundown.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

data class InstalledApp(val packageName: String, val label: String)

/**
 * Launchable apps, as package visibility lets us see them: the `<queries>`
 * launcher intent is exactly the set a person could have opened, which is the
 * set worth closing.
 */
object Apps {
    private val icons = ConcurrentHashMap<String, ImageBitmap>()
    private val labels = ConcurrentHashMap<String, String>()

    suspend fun launchable(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.packageName }
            .distinct()
            .filter { it != context.packageName }
            .map { InstalledApp(it, label(context, it)) }
            .sortedBy { it.label.lowercase() }
    }

    fun label(context: Context, pkg: String): String = labels.getOrPut(pkg) {
        runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    fun isInstalled(context: Context, pkg: String): Boolean = runCatching {
        context.packageManager.getApplicationInfo(pkg, 0); true
    }.getOrDefault(false)

    suspend fun icon(context: Context, pkg: String): ImageBitmap? {
        icons[pkg]?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(pkg)
                    .toBitmap(96, 96)
                    .asImageBitmap()
                    .also { icons[pkg] = it }
            }.getOrNull()
        }
    }

    /** Whether a class name from an accessibility event is one of the package's activities. */
    fun isActivity(pm: PackageManager, pkg: String, cls: String): Boolean = runCatching {
        pm.getActivityInfo(android.content.ComponentName(pkg, cls), 0); true
    }.getOrDefault(false)
}
