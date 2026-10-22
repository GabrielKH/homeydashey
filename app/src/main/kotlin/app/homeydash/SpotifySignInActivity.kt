package app.homeydash

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import app.homeydash.sonos.SpotifySearch

/**
 * Spotify's sign-in page in a window of its own (a WebView drawn inside a Compose popup stayed
 * black), in the app so the kiosk lock lets it open. It closes as soon as Spotify sends the page
 * back to [SpotifySearch.REDIRECT], and hands that address, with its code, back as the result.
 */
class SpotifySignInActivity : Activity() {

    private var done = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL) ?: return finish()
        val pad = (16 * resources.displayMetrics.density).toInt()

        val status = TextView(this).apply {
            text = "Loading Spotify…"
            textSize = 16f
            setTextColor(Color.BLACK)
        }
        val cancel = Button(this).apply {
            text = "Cancel"
            setOnClickListener { finish() }
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
            setBackgroundColor(Color.WHITE)
            addView(status, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
            addView(cancel, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
        }
        val web = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                    caught(request.url.toString())

                override fun onPageStarted(view: WebView, started: String?, favicon: Bitmap?) {
                    if (!caught(started)) super.onPageStarted(view, started, favicon)
                }

                override fun onPageFinished(view: WebView, finished: String?) {
                    if (!done) status.text = "Sign in with the Spotify account that's in the Sonos app."
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame && !caught(request.url.toString())) {
                        status.text = "Couldn't load Spotify: ${error.description}"
                    }
                }

                override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
                    handler.cancel()
                    status.text = "Couldn't load Spotify: the secure connection failed (is the tablet's clock right?)"
                }
            }
        }
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(web, true)
        }
        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.WHITE)
                addView(bar, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
                addView(web, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            },
        )
        web.loadUrl(url)
    }

    /** Whether [target] is the way back from Spotify; the first time, it's handed back and the window closes. */
    private fun caught(target: String?): Boolean {
        if (target == null || !target.startsWith(SpotifySearch.REDIRECT)) return false
        if (!done) {
            done = true
            setResult(RESULT_OK, Intent().setData(Uri.parse(target)))
            finish()
        }
        return true
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
