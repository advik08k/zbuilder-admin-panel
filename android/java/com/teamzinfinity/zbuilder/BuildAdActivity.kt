package com.teamzinfinity.zbuilder

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.os.CountDownTimer
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
        private const val DEFAULT_TIMER_SECONDS = 5
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
            runOnUiThread { handleConfig(json) }
        }.start()
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
    private fun handleConfig(raw: String?) {
        if (raw.isNullOrBlank()) {
            // No network / 404 / bad JSON - never trap the user on a broken ad.
            // NO reward: otherwise airplane mode would farm free builds.
            Toast.makeText(this, "Ad unavailable", Toast.LENGTH_SHORT).show()
            finishSafely(grantReward = false)
            return
        }

        val cfg = try {
            JSONObject(raw)
        } catch (e: Exception) {
            finishSafely(grantReward = false)
            return
        }

        val isActive = cfg.optBoolean("isActive", false)
        if (!isActive) {
            // Admin switched ads off from the panel -> nothing to watch.
            finishSafely(grantReward = true)
            return
        }

        redirectLink = cfg.optString("redirectLink", "").trim()
        val imageUrl = cfg.optString("adImageUrl", "").trim()
        val timerSeconds = cfg
            .optInt("timerSeconds", DEFAULT_TIMER_SECONDS)
            .coerceIn(1, 120)

        if (imageUrl.isBlank()) {
            // Active but no image yet - don't show a black screen.
            Toast.makeText(this, "Ad not configured", Toast.LENGTH_SHORT).show()
            finishSafely(grantReward = true)
            return
        }

        tapHint.visibility =
            if (redirectLink.isNullOrBlank()) View.GONE else View.VISIBLE

        loadImage(imageUrl)
        startTimer(timerSeconds)
    }

    // ------------------------------------------------------------------
    // 3. IMAGE (GLIDE)
    // ------------------------------------------------------------------
    private fun loadImage(url: String) {
        progressBar.visibility = View.VISIBLE

        Glide.with(this)
            .load(url)
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
                val left = ((millisUntilFinished / 1000L) + 1).toInt()
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
