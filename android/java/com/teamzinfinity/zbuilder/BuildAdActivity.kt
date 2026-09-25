package com.teamzinfinity.zbuilder

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Custom Fallback Ad screen.
 *
 * Config is fetched DIRECTLY from raw.githubusercontent.com - the Render
 * server is never touched on this path, so there is no cold start.
 *
 *   isActive      -> false means "ad switched off": we finish instantly.
 *   adImageUrl    -> loaded full screen with Glide (centerCrop)
 *   redirectLink  -> fired when the user taps the image
 *   timerSeconds  -> "Skip in N" pill, top-right, over the image
 *
 * The back button stays dead until the timer reaches zero.
 *
 * Optional extras (kept for drop-in parity with FallbackAdActivity):
 *   REWARD_TYPE : Int  -> 1 = +1 build, 2 = +3 builds, granted on dismiss.
 */
@SuppressLint("SetTextI18n")
class BuildAdActivity : AppCompatActivity() {

    companion object {
        // Bypasses the Render server entirely -> instant load.
        private const val CONFIG_URL =
            "https://raw.githubusercontent.com/advik08k/ZBuilder-Plugins/main/ad-config.json"

        private const val CONNECT_TIMEOUT_MS = 6000
        private const val READ_TIMEOUT_MS = 6000

        // Ad creative download: generous, because Glide's own 2.5s default
        // was killing the ad on a few-hundred-KB image.
        private const val IMAGE_READ_TIMEOUT_MS = 15000
        private const val MAX_IMAGE_BYTES = 8 * 1024 * 1024

        // One dropped connection = black ad screen, so every hop is retried.
        private const val IMAGE_DOWNLOAD_ATTEMPTS = 3
        private const val RETRY_BACKOFF_MS = 400

        // Hosts like ibb.co / imgbb / facebook share a *page* URL, not the
        // image itself. We follow a few hops of HTML looking for og:image.
        private const val MAX_IMAGE_HOPS = 3
        private const val HTML_PROBE_LIMIT = 64 * 1024   // og:image lives in <head>

        // Wait time is driven by WHICH reward was asked for, not by config:
        //   REWARD_TYPE 1 -> 20s -> +1 free build
        //   REWARD_TYPE 2 -> 60s -> +3 free builds
        private const val WAIT_SECONDS_REWARD_1 = 20
        private const val WAIT_SECONDS_REWARD_2 = 60

        private const val TAG = "BuildAd"
    }

    private lateinit var adImage: ImageView
    private lateinit var progressBar: ProgressBar
    private lateinit var tvSkip: TextView
    private lateinit var tapHint: TextView

    private var countdown: CountDownTimer? = null
    private var canSkip = false
    private var redirectLink: String? = null
    private var rewardType: Int = 0          // 0 = no reward attached

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_build_ad)

        adImage = findViewById(R.id.ivAdImage)
        progressBar = findViewById(R.id.progressBar)
        tvSkip = findViewById(R.id.tvSkip)
        tapHint = findViewById(R.id.tvTapHint)

        rewardType = intent.getIntExtra("REWARD_TYPE", 0)

        // Push the pill below the status bar so it always sits on the image,
        // never underneath the clock.
        applyTopInsetToSkipPill()

        // Locked state: the pill is a label, not a button, until the timer ends.
        setSkipEnabled(false)
        tvSkip.text = "Loading…"

        // Tapping the AD IMAGE opens the redirect link.
        adImage.setOnClickListener {
            val link = redirectLink
            if (link.isNullOrBlank()) {
                Toast.makeText(this, "No redirect link configured", Toast.LENGTH_SHORT).show()
            } else {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
                } catch (e: Exception) {
                    Toast.makeText(this, "Couldn't open link", Toast.LENGTH_SHORT).show()
                }
            }
        }

        fetchConfig()
    }

    // ------------------------------------------------------------------
    // 1. FETCH CONFIG DIRECTLY FROM GITHUB RAW
    // ------------------------------------------------------------------
    private fun fetchConfig() {
        Thread {
            val json = fetchJsonWithFallbacks()

            // Resolve adImageUrl OFF the UI thread: it may be a hosting page
            // (ibb.co/xxx) that has to be opened to find the real image URL,
            // and the image itself needs downloading with a sane timeout.
            var resolved: ResolvedImage? = null
            if (!json.isNullOrBlank()) {
                try {
                    val candidate = JSONObject(json).optString("adImageUrl", "").trim()
                    if (candidate.isNotEmpty()) resolved = resolveImageUrl(candidate)
                } catch (ignored: Exception) {
                    // Bad JSON - handleConfig() below reports it properly.
                }
            }

            Log.d(TAG, "resolve done: url=${resolved?.url ?: "-"} " +
                    "bytes=${resolved?.bytes?.size ?: 0}")

            runOnUiThread { handleConfig(json, resolved) }
        }.start()
    }

    /**
     * Turns a share/page URL into a direct image URL, downloading the bytes
     * while it's at it.
     *
     * e.g.  https://ibb.co/HD62R8mf          (text/html)
     *    -> https://i.ibb.co/fYfSZwmz/...jpg (image/jpeg, bytes captured)
     *
     * The bytes come along because Glide's own HttpUrlFetcher times out after
     * 2.5s - far too short for a few-hundred-KB ad creative. We download with
     * our own, much larger timeout and hand Glide the buffer instead.
     *
     * Returns whatever URL we ended on; [ResolvedImage.bytes] is null whenever
     * anything was inconclusive, so Glide still gets the final say - we never
     * turn a working URL into a broken one.
     */
    private fun resolveImageUrl(candidate: String): ResolvedImage {
        var url = candidate
        repeat(MAX_IMAGE_HOPS) {
            val probe = probeWithRetry(url) ?: return ResolvedImage(url, null)
            if (probe.isImage) return ResolvedImage(url, probe.bytes)
            if (!probe.isHtml) return ResolvedImage(url, null)

            val next = extractOpenGraphImage(probe.body) ?: return ResolvedImage(url, null)
            if (next.isBlank()) return ResolvedImage(url, null)
            url = next
        }
        return ResolvedImage(url, null)
    }

    /**
     * Ad CDNs drop connections far more often than they should (33% packet
     * loss is normal on mobile), and one dropped read means a black screen.
     * So: retry the same hop a few times before we give up on it.
     *
     * Returns as soon as a hop lands *with* its payload - an image hop that
     * only got the headers counts as a miss and is retried.
     */
    private fun probeWithRetry(rawUrl: String): ProbedContent? {
        var last: ProbedContent? = null
        repeat(IMAGE_DOWNLOAD_ATTEMPTS) { attempt ->
            val probe = probeContent(rawUrl)
            if (probe != null && (!probe.isImage || probe.bytes != null)) return probe
            last = probe
            Log.w(TAG, "probe miss ${attempt + 1}/$IMAGE_DOWNLOAD_ATTEMPTS url=$rawUrl " +
                    "got=${if (probe == null) "no-response" else "image-without-bytes"}")
            if (attempt < IMAGE_DOWNLOAD_ATTEMPTS - 1) {
                try {
                    Thread.sleep(RETRY_BACKOFF_MS.toLong())
                } catch (ie: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return probe
                }
            }
        }
        return last
    }

    private class ResolvedImage(val url: String, val bytes: ByteArray?)

    private class ProbedContent(
        val isImage: Boolean,
        val isHtml: Boolean,
        val body: String,
        val bytes: ByteArray?
    )

    /** GET (not HEAD - many hosts reject it) and classify by Content-Type. */
    private fun probeContent(rawUrl: String): ProbedContent? {
        var conn: HttpURLConnection? = null
        return try {
            val c = URL(rawUrl).openConnection() as HttpURLConnection
            conn = c
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.readTimeout = IMAGE_READ_TIMEOUT_MS
            c.requestMethod = "GET"
            c.instanceFollowRedirects = true
            c.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Mobile"
            )

            if (c.responseCode != HttpURLConnection.HTTP_OK) return null

            val ct = (c.contentType ?: "").lowercase()
            when {
                ct.startsWith("image/") ->
                    ProbedContent(true, false, "", readBytes(c.inputStream))
                ct.contains("html") ->
                    ProbedContent(false, true, readLimited(c.inputStream), null)
                else -> ProbedContent(false, false, "", null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "probe failed url=$rawUrl -> ${e.javaClass.simpleName}: ${e.message}")
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun readLimited(input: InputStream): String = try {
        val buf = CharArray(8192)
        val sb = StringBuilder()
        input.bufferedReader().use { r ->
            while (sb.length < HTML_PROBE_LIMIT) {
                val n = r.read(buf, 0, minOf(buf.size, HTML_PROBE_LIMIT - sb.length))
                if (n <= 0) break
                sb.append(buf, 0, n)
            }
        }
        sb.toString()
    } catch (e: Exception) {
        ""
    }

    private fun readBytes(input: InputStream): ByteArray? = try {
        input.use { ins ->
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            var total = 0
            while (true) {
                val n = ins.read(buf)
                if (n <= 0) break
                total += n
                if (total > MAX_IMAGE_BYTES) return null   // absurdly large: refuse
                out.write(buf, 0, n)
            }
            out.toByteArray()
        }
    } catch (e: Exception) {
        null
    }

    /** Pulls og:image (then twitter:image) out of a page's <head>. */
    private fun extractOpenGraphImage(html: String): String? {
        val patterns = listOf(
            Regex("(?i)<meta[^>]+property\\s*=\\s*[\"']og:image[\"'][^>]+content\\s*=\\s*[\"']([^\"']+)[\"']"),
            Regex("(?i)<meta[^>]+content\\s*=\\s*[\"']([^\"']+)[\"'][^>]+property\\s*=\\s*[\"']og:image[\"']"),
            Regex("(?i)<meta[^>]+name\\s*=\\s*[\"']twitter:image[\"'][^>]+content\\s*=\\s*[\"']([^\"']+)[\"']"),
            Regex("(?i)<meta[^>]+content\\s*=\\s*[\"']([^\"']+)[\"'][^>]+name\\s*=\\s*[\"']twitter:image[\"']")
        )
        for (rx in patterns) {
            rx.find(html)?.let { m ->
                return m.groupValues[1].trim().replace("&amp;", "&")
            }
        }
        return null
    }

    /** Tries the plain URL first, then a cache-busted one (raw CDN caches ~5 min). */
    private fun fetchJsonWithFallbacks(): String? {
        val candidates = listOf(
            CONFIG_URL,
            "$CONFIG_URL?cb=${System.currentTimeMillis()}"
        )
        for (url in candidates) {
            fetchJson(url)?.let { return it }
        }
        return null
    }

    private fun fetchJson(rawUrl: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val u = URL(rawUrl)
            val c = u.openConnection() as HttpURLConnection
            conn = c
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.readTimeout = READ_TIMEOUT_MS
            c.requestMethod = "GET"
            c.setRequestProperty("Cache-Control", "no-cache")
            c.instanceFollowRedirects = true

            if (c.responseCode != HttpURLConnection.HTTP_OK) return null
            c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    // ------------------------------------------------------------------
    // 2. ACT ON THE CONFIG
    // ------------------------------------------------------------------
    /**
     * 20s of waiting earns 1 free build, 60s earns 3. Anything unrecognised
     * (rewardType 0 = opened without an extra, e.g. a deep link) gets the
     * cheap tier so nobody ever waits a full minute for nothing.
     */
    private fun waitSecondsFor(type: Int): Int =
        if (type == 2) WAIT_SECONDS_REWARD_2 else WAIT_SECONDS_REWARD_1

    private fun handleConfig(raw: String?, resolved: ResolvedImage?) {
        if (raw.isNullOrBlank()) {
            // No network / 404 / bad JSON - never trap the user on a broken ad.
            // NO reward: otherwise airplane mode would farm free builds.
            Log.w(TAG, "exit: config fetch returned nothing (network/404/parse)")
            Toast.makeText(this, "Ad unavailable", Toast.LENGTH_SHORT).show()
            finishSafely(grantReward = false)
            return
        }

        val cfg = try {
            JSONObject(raw)
        } catch (e: Exception) {
            Log.w(TAG, "exit: config JSON malformed -> ${e.message}")
            finishSafely(grantReward = false)
            return
        }

        val isActive = cfg.optBoolean("isActive", false)
        if (!isActive) {
            // Admin switched ads off from the panel -> nothing to watch.
            Log.i(TAG, "exit: isActive=false -> closing with reward")
            finishSafely(grantReward = true)
            return
        }

        redirectLink = cfg.optString("redirectLink", "").trim()
        // Prefer the resolved direct-image URL; fall back to whatever was
        // configured if resolution produced nothing.
        val imageUrl = (resolved?.url ?: cfg.optString("adImageUrl", "").trim()).trim()

        // Config no longer decides the wait - the reward asked for does.
        val timerSeconds = waitSecondsFor(rewardType)

        Log.d(TAG, "config ok: rewardType=$rewardType wait=${timerSeconds}s image=$imageUrl")

        if (imageUrl.isBlank()) {
            // Active but no image yet - don't show a black screen.
            Log.i(TAG, "exit: image URL blank -> closing with reward")
            Toast.makeText(this, "Ad not configured", Toast.LENGTH_SHORT).show()
            finishSafely(grantReward = true)
            return
        }

        tapHint.visibility =
            if (redirectLink.isNullOrBlank()) View.GONE else View.VISIBLE

        loadImage(imageUrl, resolved?.bytes)
        startTimer(timerSeconds)
    }

    // ------------------------------------------------------------------
    // 3. IMAGE (GLIDE)
    // ------------------------------------------------------------------
    /**
     * @param bytes already-downloaded creative, or null to let Glide fetch it.
     *              Passing bytes sidesteps Glide's 2.5s HttpUrlFetcher timeout,
     *              which was aborting the ad on a few-hundred-KB image.
     */
    private fun loadImage(url: String, bytes: ByteArray?) {
        progressBar.visibility = View.VISIBLE

        val model: Any = if (bytes != null && bytes.isNotEmpty()) bytes else url
        Log.d(TAG, "loading image from ${if (bytes != null) "buffer (${bytes.size} B)" else url}")

        Glide.with(this)
            .load(model)
            .diskCacheStrategy(DiskCacheStrategy.NONE)   // ads change often - always refetch
            .dontAnimate()
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    if (isFinishing || isDestroyed) return true
                    progressBar.visibility = View.GONE
                    Log.w(TAG, "exit: image load FAILED -> ${e?.message ?: "unknown"}")
                    Toast.makeText(
                        this@BuildAdActivity,
                        "Ad image failed to load",
                        Toast.LENGTH_SHORT
                    ).show()
                    // Image failed AFTER the ad was shown - reward is the
                    // user's problem to earn by waiting, not given freely.
                    finishSafely(grantReward = false)
                    return true
                }

                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    progressBar.visibility = View.GONE
                    Log.i(TAG, "image ready (${dataSource})")
                    return false   // let Glide set the drawable on the ImageView
                }
            })
            .into(adImage)
    }

    // ------------------------------------------------------------------
    // 4. TIMER  ->  "Skip in 5" ... "Skip Ad"
    // ------------------------------------------------------------------
    private fun startTimer(seconds: Int) {
        countdown?.cancel()
        canSkip = false
        setSkipEnabled(false)
        tvSkip.text = "Skip in $seconds"

        countdown = object : CountDownTimer(seconds * 1000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                // First tick reports the FULL duration (5000ms for a 5s timer),
                // so plain division gives 5,4,3,2,1 -> then onFinish() flips it.
                val left = (millisUntilFinished / 1000L).toInt()
                tvSkip.text = "Skip in $left"
            }

            override fun onFinish() {
                canSkip = true
                tvSkip.text = "Skip Ad"          // or "✕" if you prefer
                setSkipEnabled(true)             // NOW it becomes clickable
            }
        }.start()
    }

    /** Enables/disables the pill as a real button. */
    private fun setSkipEnabled(enabled: Boolean) {
        tvSkip.isClickable = enabled
        tvSkip.isFocusable = enabled
        tvSkip.setTextColor(Color.WHITE)
        if (enabled) {
            tvSkip.setBackgroundResource(R.drawable.bg_skip_pill)
            tvSkip.alpha = 1f
            tvSkip.setOnClickListener { finishSafely() }
        } else {
            // Swallow taps so nothing weird happens during the countdown.
            tvSkip.setOnClickListener { /* blocked until timer = 0 */ }
        }
    }

    // ------------------------------------------------------------------
    // 5. BACK BUTTON IS DEAD UNTIL THE TIMER HITS ZERO
    // ------------------------------------------------------------------
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (!canSkip) {
            val remaining = tvSkip.text.toString()
            Toast.makeText(
                this,
                "Please wait - $remaining",
                Toast.LENGTH_SHORT
            ).show()
            return          // <- do NOT call super: the ad stays up
        }
        // Timer finished - back is now equivalent to skipping.
        finishSafely(grantReward = true)
    }

    // ------------------------------------------------------------------
    // dismiss + optional reward (drop-in parity with FallbackAdActivity)
    // ------------------------------------------------------------------
    private fun finishSafely(grantReward: Boolean = true) {
        Log.i(TAG, "finishSafely reward=$grantReward canSkip=$canSkip rewardType=$rewardType")
        if (grantReward) grantRewardIfNeeded()
        finish()
    }

    private fun grantRewardIfNeeded() {
        if (rewardType == 0) return
        val prefs = getSharedPreferences(
            "com.teamzinfinity.zbuilder_preferences", MODE_PRIVATE
        )
        val current = prefs.getInt("free_builds_count", 0)
        val gained = if (rewardType == 1) 1 else 3
        prefs.edit().putInt("free_builds_count", current + gained).apply()
        Toast.makeText(
            this,
            "Earned $gained Free Build${if (gained > 1) "s" else ""}!",
            Toast.LENGTH_LONG
        ).show()
    }

    // ------------------------------------------------------------------
    private fun applyTopInsetToSkipPill() {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            val statusBarPx = resources.getDimensionPixelSize(resourceId)
            val extra = (16 * resources.displayMetrics.density).toInt()  // breathing room
            (tvSkip.layoutParams as android.view.ViewGroup.MarginLayoutParams)
                .topMargin = statusBarPx + extra
            tvSkip.requestLayout()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        countdown?.cancel()
        if (!isFinishing) {
            // Activity rotated away - make sure Glide stops targeting the view.
            try { Glide.with(this).clear(adImage) } catch (_: Exception) {}
        }
    }
}
