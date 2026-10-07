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

    // The broker binder, held until its process dies. Warden's provider answers
    // as soon as it has the binder (waiting for the broker's handshake if Warden
    // itself had to be started), so a cold lookup costs one round trip.
    @Volatile private var cached: IWarden? = null

    private fun broker(context: Context): IWarden? {
        cached?.takeIf { it.asBinder().isBinderAlive }?.let { return it }
        return runCatching {
            val uri = Uri.parse("content://$AUTHORITY")
            val reply = context.contentResolver.call(uri, "getBinder", null, null) ?: return null
            val binder = reply.getBinder("binder") ?: return null
            val svc = IWarden.Stub.asInterface(binder)
            binder.linkToDeath({ if (cached?.asBinder() === binder) cached = null }, 0)
            cached = svc
            svc
        }.getOrNull()
    }

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
