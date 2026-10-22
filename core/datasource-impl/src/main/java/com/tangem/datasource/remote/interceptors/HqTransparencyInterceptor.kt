package com.tangem.datasource.remote.interceptors

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import okhttp3.Interceptor
import okhttp3.Request
import okhttp3.Response
import okio.Buffer
import java.lang.ref.WeakReference
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * Real-time, 100% RAM-only outbound network transparency monitor.
 *
 * - Never writes anything to disk.
 * - Uses metadata-only inspection by default for blockchain RPCs and large/streaming/one-shot payloads
 *   so network observation never mutates or interferes with transaction submission.
 * - Inspects URL host (strict domain matching), path, query parameters, and sensitive request headers
 *   (`card_id`, `card_public_key`, `Authorization`, `DPoP`) to surface Tangem HQ and card-verification calls.
 */
object HqTransparencyInterceptor : Interceptor {

    data class AuditEntry(
        val timestamp: String,
        val method: String,
        val host: String,
        val path: String,
        val queryParams: List<Pair<String, String>>,
        val inspectedHeaders: List<Pair<String, String>>,
        val requestBodySummary: String,
        val responseStatus: String,
        val durationMs: Long,
        val isCardAttestationCall: Boolean,
        val isTangemHqCall: Boolean,
    )

    private const val MAX_ENTRIES = 100
    private const val MAX_INSPECTED_BODY_BYTES = 4_096L
    private const val MAX_DISPLAYED_BODY_CHARS = 600
    private const val TOAST_COOLDOWN_MS = 6_000L

    private val SENSITIVE_HEADER_NAMES = setOf(
        "card_id",
        "card_public_key",
        "authorization",
        "dpop",
    )

    private val CARD_QUERY_PARAM_NAMES = setOf(
        "cardid",
        "cardpublickey",
        "cid",
        "walletid",
    )

    private val mainHandler = Handler(Looper.getMainLooper())
    private val sessionEntries = CopyOnWriteArrayList<AuditEntry>()
    private val pendingCardHqEntries = CopyOnWriteArrayList<AuditEntry>()

    @Volatile
    private var currentActivityRef: WeakReference<Activity>? = null

    @Volatile
    private var activeDialogRef: WeakReference<AlertDialog>? = null

    @Volatile
    private var lastToastTimeMs: Long = 0L

    fun registerApplication(application: Application) {
        application.registerActivityLifecycleCallbacks(
            object : Application.ActivityLifecycleCallbacks {
                override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
                    currentActivityRef = WeakReference(activity)
                }

                override fun onActivityStarted(activity: Activity) {
                    currentActivityRef = WeakReference(activity)
                }

                override fun onActivityResumed(activity: Activity) {
                    currentActivityRef = WeakReference(activity)
                    if (pendingCardHqEntries.isNotEmpty()) {
                        showOrUpdateCardHqDialog()
                    }
                }

                override fun onActivityPaused(activity: Activity) = Unit
                override fun onActivityStopped(activity: Activity) = Unit
                override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
                override fun onActivityDestroyed(activity: Activity) {
                    if (currentActivityRef?.get() === activity) {
                        currentActivityRef = null
                    }
                }
            },
        )
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startNs = System.nanoTime()
        val timestamp = SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(Date())

        val host = request.url.host
        val path = request.url.encodedPath
        val queryParams = (0 until request.url.querySize).map { i ->
            request.url.queryParameterName(i) to (request.url.queryParameterValue(i) ?: "")
        }
        val inspectedHeaders = extractSensitiveHeaders(request)

        val isVerifyHost = isDomainMatch(host, "verify.tangem.com")
        val isHqHost = isVerifyHost ||
            isDomainMatch(host, "tangem.com") ||
            isDomainMatch(host, "tangem-tech.com") ||
            isDomainMatch(host, "tangem.org")

        val hasCardHeader = inspectedHeaders.any { (name, _) ->
            name.equals("card_id", ignoreCase = true) ||
                name.equals("card_public_key", ignoreCase = true)
        }
        val hasCardQuery = queryParams.any { (k, _) ->
            k.lowercase(Locale.US) in CARD_QUERY_PARAM_NAMES
        }
        val isCardCall = isVerifyHost || hasCardHeader || hasCardQuery
        val isHqCall = isCardCall || isHqHost

        val bodySummary = summarizeRequestBody(request = request, allowSmallBodyPreview = isHqCall)

        return try {
            val response = chain.proceed(request)
            val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
            val entry = AuditEntry(
                timestamp = timestamp,
                method = request.method,
                host = host,
                path = path,
                queryParams = queryParams,
                inspectedHeaders = inspectedHeaders,
                requestBodySummary = bodySummary,
                responseStatus = "HTTP ${response.code}",
                durationMs = tookMs,
                isCardAttestationCall = isCardCall,
                isTangemHqCall = isHqCall,
            )
            recordAndNotify(entry)
            response
        } catch (e: Exception) {
            val tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)
            val entry = AuditEntry(
                timestamp = timestamp,
                method = request.method,
                host = host,
                path = path,
                queryParams = queryParams,
                inspectedHeaders = inspectedHeaders,
                requestBodySummary = bodySummary,
                responseStatus = "BLOCKED / FAILED (${e.javaClass.simpleName})",
                durationMs = tookMs,
                isCardAttestationCall = isCardCall,
                isTangemHqCall = isHqCall,
            )
            recordAndNotify(entry)
            throw e
        }
    }

    private fun isDomainMatch(host: String, domain: String): Boolean {
        return host.equals(domain, ignoreCase = true) ||
            host.endsWith(".$domain", ignoreCase = true)
    }

    private fun extractSensitiveHeaders(request: Request): List<Pair<String, String>> {
        val headers = request.headers
        val matched = mutableListOf<Pair<String, String>>()
        for (i in 0 until headers.size) {
            val name = headers.name(i)
            if (name.lowercase(Locale.US) in SENSITIVE_HEADER_NAMES) {
                matched.add(name to headers.value(i))
            }
        }
        return matched
    }

    /**
     * Safely summarizes the request body without interfering with transaction broadcasts or streaming requests:
     * - Never touches one-shot or duplex request bodies.
     * - Uses metadata-only reporting for non-HQ (blockchain RPC) calls or bodies exceeding [MAX_INSPECTED_BODY_BYTES].
     */
    private fun summarizeRequestBody(request: Request, allowSmallBodyPreview: Boolean): String {
        val body = request.body ?: return "EMPTY (0 bytes)"
        if (body.isOneShot()) return "METADATA ONLY (one-shot body, not buffered)"
        if (body.isDuplex()) return "METADATA ONLY (duplex body, not buffered)"

        val contentLength = runCatching { body.contentLength() }.getOrDefault(-1L)
        val contentType = body.contentType()?.toString() ?: "unknown"
        if (contentLength == 0L) return "EMPTY (0 bytes)"

        if (!allowSmallBodyPreview) {
            val sizeLabel = if (contentLength >= 0L) "$contentLength bytes" else "unknown length"
            return "METADATA ONLY ($sizeLabel, type=$contentType; body not buffered for RPC safety)"
        }

        if (contentLength < 0L || contentLength > MAX_INSPECTED_BODY_BYTES) {
            val sizeLabel = if (contentLength >= 0L) "$contentLength bytes" else "unknown length"
            return "METADATA ONLY ($sizeLabel exceeds ${MAX_INSPECTED_BODY_BYTES}B inspection cap, type=$contentType)"
        }

        return runCatching {
            val buffer = Buffer()
            body.writeTo(buffer)
            if (buffer.size == 0L) {
                "EMPTY (0 bytes)"
            } else {
                val raw = buffer.readString(StandardCharsets.UTF_8)
                if (raw.length > MAX_DISPLAYED_BODY_CHARS) {
                    raw.take(MAX_DISPLAYED_BODY_CHARS) + "... (${buffer.size} bytes total)"
                } else {
                    raw
                }
            }
        }.getOrDefault("METADATA ONLY (non-readable body, type=$contentType)")
    }

    private fun recordAndNotify(entry: AuditEntry) {
        while (sessionEntries.size >= MAX_ENTRIES) {
            sessionEntries.removeAt(0)
        }
        sessionEntries.add(entry)

        if (entry.isCardAttestationCall) {
            pendingCardHqEntries.add(entry)
            mainHandler.post { showOrUpdateCardHqDialog() }
        } else if (entry.isTangemHqCall) {
            val now = System.currentTimeMillis()
            if (now - lastToastTimeMs > TOAST_COOLDOWN_MS) {
                lastToastTimeMs = now
                mainHandler.post {
                    val activity = currentActivityRef?.get() ?: return@post
                    if (!activity.isFinishing && !activity.isDestroyed) {
                        Toast.makeText(
                            activity.applicationContext,
                            "📡 HQ Public API: ${entry.method} ${entry.host}${entry.path}",
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                }
            }
        }
    }

    private fun showOrUpdateCardHqDialog() {
        val activity = currentActivityRef?.get() ?: return
        if (activity.isFinishing || activity.isDestroyed) return
        if (pendingCardHqEntries.isEmpty()) return

        activeDialogRef?.get()?.let { existing ->
            if (existing.isShowing) {
                runCatching { existing.dismiss() }
            }
        }

        val message = buildCardHqReport(pendingCardHqEntries.toList())
        val dialog = AlertDialog.Builder(activity)
            .setTitle("📡 Data Sent to Tangem HQ")
            .setMessage(message)
            .setPositiveButton("OK (Acknowledge)") { d, _ ->
                pendingCardHqEntries.clear()
                d.dismiss()
            }
            .setNeutralButton("Full Session Log (${sessionEntries.size})") { d, _ ->
                d.dismiss()
                showFullSessionLogDialog(activity)
            }
            .setCancelable(true)
            .create()

        activeDialogRef = WeakReference(dialog)
        runCatching { dialog.show() }
    }

    private fun showFullSessionLogDialog(activity: Activity) {
        if (activity.isFinishing || activity.isDestroyed) return

        val entries = sessionEntries.toList().asReversed()
        val text = if (entries.isEmpty()) {
            "No network requests recorded in this session."
        } else {
            buildString {
                append("RAM-Only Network Audit Log (${entries.size} requests, newest first):\n")
                append("(Zero bytes written to disk)\n\n")
                entries.forEachIndexed { idx, e ->
                    val badge = when {
                        e.isCardAttestationCall -> "🪪 [CARD VERIFY]"
                        e.isTangemHqCall -> "🏢 [TANGEM API]"
                        else -> "⛓️ [BLOCKCHAIN RPC]"
                    }
                    append("${idx + 1}. $badge [${e.timestamp}] ${e.responseStatus} (${e.durationMs}ms)\n")
                    append("   ${e.method} https://${e.host}${e.path}\n")
                    if (e.inspectedHeaders.isNotEmpty()) {
                        append("   Auth/Card Headers:\n")
                        e.inspectedHeaders.forEach { (k, v) ->
                            append("     • $k = $v\n")
                        }
                    } else {
                        append("   Auth/Card Headers: NONE\n")
                    }
                    if (e.queryParams.isNotEmpty()) {
                        append("   Query Params:\n")
                        e.queryParams.forEach { (k, v) ->
                            append("     • $k = $v\n")
                        }
                    } else {
                        append("   Query Params: NONE\n")
                    }
                    append("   Request Body: ${e.requestBodySummary}\n\n")
                }
            }
        }

        runCatching {
            AlertDialog.Builder(activity)
                .setTitle("🔍 Session Network Audit Log (RAM Only)")
                .setMessage(text)
                .setPositiveButton("Close") { d, _ ->
                    pendingCardHqEntries.clear()
                    d.dismiss()
                }
                .setNeutralButton("Clear RAM Log") { d, _ ->
                    sessionEntries.clear()
                    pendingCardHqEntries.clear()
                    d.dismiss()
                }
                .show()
        }
    }

    private fun buildCardHqReport(entries: List<AuditEntry>): String = buildString {
        append("The app communicated with Tangem's verification server (${entries.size} request(s)).\n")
        append("Here is the EXACT payload transmitted:\n\n")

        entries.forEachIndexed { index, e ->
            val purpose = when {
                e.path.contains("artwork", ignoreCase = true) ->
                    "Fetches card visual artwork / verifies backup card issuer signature"
                e.path.contains("verify", ignoreCase = true) ->
                    "Factory Chip Attestation (verifies genuine Tangem silicon)"
                else -> "Tangem Verification Endpoint"
            }
            append("━━ Request #${index + 1} [${e.timestamp}] ━━\n")
            append("• Endpoint: ${e.method} https://${e.host}${e.path}\n")
            append("• Result: ${e.responseStatus} (${e.durationMs}ms)\n")
            append("• Purpose: $purpose\n")
            append("• Auth / Card Headers Sent:\n")
            if (e.inspectedHeaders.isEmpty()) {
                append("    (none)\n")
            } else {
                e.inspectedHeaders.forEach { (k, v) ->
                    append("    - $k: $v\n")
                }
            }
            append("• Query Parameters Sent:\n")
            if (e.queryParams.isEmpty()) {
                append("    (none)\n")
            } else {
                e.queryParams.forEach { (k, v) ->
                    append("    - $k: $v\n")
                }
            }
            append("• HTTP Request Body: ${e.requestBodySummary}\n\n")
        }

        append("ℹ️ TRANSPARENCY MONITOR NOTES:\n")
        append("• Scope: Retrospective RAM-only HTTP metadata & bounded body viewer (<= ${MAX_INSPECTED_BODY_BYTES}B)\n")
        append("• Inspected Headers: card_id, card_public_key, Authorization, DPoP\n")
        append("• Disk Logging: DISABLED (RAM-only session buffer)")
    }
}
