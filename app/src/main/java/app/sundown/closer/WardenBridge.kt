package app.sundown.closer

import android.content.Context
import android.net.Uri
import app.warden.api.IWarden

/**
 * Bridge to the Warden privilege broker.
 *
 * Warden runs a service with ADB-shell privileges; through it we can
 * `am force-stop` any package — which, unlike our accessibility trick, works
 * while the phone is locked, while the app is on screen, and while it holds a
 * foreground service. This is the reliable close path when Warden is installed,
 * running, and Sundown has been granted access in the Warden app.
 *
 * We obtain the broker binder from Warden's exported ContentProvider; the broker
 * still enforces our grant on every call, so an ungranted Sundown gets DENIED.
 */
object WardenBridge {
    private const val AUTHORITY = "app.warden.broker"

    enum class Result { CLOSED, DENIED, UNAVAILABLE, FAILED }

    private fun broker(context: Context): IWarden? = runCatching {
        val uri = Uri.parse("content://$AUTHORITY")
        val reply = context.contentResolver.call(uri, "getBinder", null, null) ?: return null
        val binder = reply.getBinder("binder") ?: return null
        if (!binder.isBinderAlive) return null
        IWarden.Stub.asInterface(binder)
    }.getOrNull()

    /** True when the broker is reachable (installed + running). */
    fun available(context: Context): Boolean =
        broker(context)?.let { runCatching { it.apiVersion() >= 1 }.getOrDefault(false) } ?: false

    /** Force-stop [pkg] through the broker. Whole-app: takes its activities too. */
    fun forceStop(context: Context, pkg: String): Result {
        val svc = broker(context) ?: return Result.UNAVAILABLE
        return try {
            val proc = svc.newProcess(arrayOf("am", "force-stop", pkg), emptyArray(), "/")
            if (proc.waitFor() == 0) Result.CLOSED else Result.FAILED
        } catch (e: SecurityException) {
            Result.DENIED          // Sundown not granted in Warden
        } catch (e: Throwable) {
            Result.FAILED
        }
    }
}
