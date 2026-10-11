package com.sync.app.glass

import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/**
 * Frontend(WebView) <-> Native(Compose) channel.
 *
 *  JS  -> Kotlin : window.GlassBridge.push(json)   (DOM state mirror, rects in CSS px)
 *  Kotlin -> JS  : window.__glassCmd({...})        (taps / text input forwarded to the existing app.js functions)
 *
 * The WebView + app.js + AndroidBridge keep running exactly as before; they remain the single source of
 * truth for playback / search / playlists. Compose only renders the glass surfaces and their controls.
 */
class GlassController(private val webView: WebView) {

    var state by mutableStateOf(GlassState())
        private set

    /** WebView.getScrollY() (px) — updated on the UI thread in the same frame the page scrolls. */
    var nativeScrollPx by mutableIntStateOf(0)
        private set
    /** window.scrollY (CSS px) from JS — fallback only, used if the WebView never reports a native scroll. */
    private var jsScroll by mutableFloatStateOf(0f)
    private var nativeWorks = false

    fun onNativeScroll(y: Int) {
        if (y > 0) nativeWorks = true
        nativeScrollPx = y
    }

    @JavascriptInterface
    fun scroll(y: Float) { jsScroll = y }

    // --- pre-planned fling path ------------------------------------------------------------------------
    // While the finger is down WebView applies the scroll inside the touch handler, so the value we read is already
    // current. After release the page keeps moving on a fling curve that is fully determined by the release
    // velocity — so instead of chasing it frame by frame we simulate that same curve (platform spline OverScroller)
    // from the exact release point and place the capsule on it, i.e. on a path known in advance (zero read-lag).
    // The simulation is discarded as soon as it disagrees with what WebView actually reports.
    private val scroller = android.widget.OverScroller(webView.context)
    private val vc = android.view.ViewConfiguration.get(webView.context)
    private var tracker: android.view.VelocityTracker? = null
    private var flinging = false

    fun onTouch(e: android.view.MotionEvent) {
        when (e.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                tracker?.recycle(); tracker = android.view.VelocityTracker.obtain().also { it.addMovement(e) }
                scroller.forceFinished(true); flinging = false
            }
            android.view.MotionEvent.ACTION_MOVE -> tracker?.addMovement(e)
            android.view.MotionEvent.ACTION_UP -> tracker?.let {
                it.addMovement(e)
                it.computeCurrentVelocity(1000, vc.scaledMaximumFlingVelocity.toFloat())
                val vy = it.yVelocity
                if (kotlin.math.abs(vy) > vc.scaledMinimumFlingVelocity) {
                    scroller.fling(0, webView.scrollY, 0, (-vy).toInt(), 0, 0, 0, 1_000_000)
                    flinging = true
                }
                it.recycle(); tracker = null
            }
            android.view.MotionEvent.ACTION_CANCEL -> { tracker?.recycle(); tracker = null }
        }
    }

    /** Current document scroll in CSS px (== dp). Reads snapshot state → callers re-run when it changes. */
    fun cssScroll(density: Float): Float {
        if (!nativeWorks) return jsScroll
        @Suppress("UNUSED_VARIABLE") val invalidateOnScroll = nativeScrollPx // snapshot read → re-run on every scroll event
        val live = webView.scrollY.toFloat()                                  // freshest value available on the UI thread
        var v = live
        if (flinging) {
            if (scroller.computeScrollOffset()) {
                val sim = scroller.currY.toFloat()
                if (kotlin.math.abs(sim - live) <= 48f) v = sim else flinging = false
            } else flinging = false
        }
        return v.coerceAtLeast(0f) / density
    }

    private val main = Handler(Looper.getMainLooper())
    private var lastJson = ""

    @JavascriptInterface
    fun push(json: String) {
        if (json == lastJson) return
        lastJson = json
        // parse on the JS bridge thread; only the already-built immutable state hops to the UI thread
        val parsed = try { GlassState.parse(json) } catch (_: Exception) { return }
        main.post { state = parsed }
    }

    fun cmd(build: JSONObject.() -> Unit) {
        val js = JSONObject().apply(build).toString()
        webView.post { webView.evaluateJavascript("window.__glassCmd&&window.__glassCmd($js)", null) }
    }

}
