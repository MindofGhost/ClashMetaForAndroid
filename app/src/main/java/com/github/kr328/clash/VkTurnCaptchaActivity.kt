package com.github.kr328.clash

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.github.kr328.clash.common.log.Log

class VkTurnCaptchaActivity : Activity() {
    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private var closeRequested = false
    private var closeChecks = 0
    private var loadGeneration = 0
    private var captchaPageLoaded = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }

        root = FrameLayout(this)
        webView = WebView(this).apply {
            setBackgroundColor(Color.WHITE)
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.loadWithOverviewMode = true
            settings.useWideViewPort = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    Log.i("VK TURN captcha WebView loaded: $url")
                    if (url?.startsWith(LOCAL_CAPTCHA_ORIGIN) == true)
                        captchaPageLoaded = true

                    scheduleCloseTextCheck()
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?,
                ) {
                    if (request?.isForMainFrame == true) {
                        Log.w(
                            "VK TURN captcha WebView error: " +
                                    "${error?.errorCode} ${error?.description} ${request.url}"
                        )

                        val requestUrl = request.url
                        if (requestUrl?.host == LOCAL_CAPTCHA_HOST &&
                            (captchaPageLoaded || requestUrl.path == LOCAL_CAPTCHA_ROOT_PATH)
                        ) {
                            Log.i("VK TURN captcha WebView closing after local page error")
                            requestClose()
                        }
                    }
                }

                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    val requestUrl = request?.url
                    if (requestUrl?.host == "127.0.0.1" &&
                        requestUrl.path == "/local-captcha-result"
                    ) {
                        Log.i("VK TURN captcha result submitted: $requestUrl")
                        requestClose()
                    }

                    return super.shouldInterceptRequest(view, request)
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                    if (consoleMessage != null) {
                        Log.i(
                            "VK TURN captcha console: ${consoleMessage.message()} " +
                                    "(${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                        )
                    }

                    scheduleCloseTextCheck()

                    return true
                }
            }
        }

        root.addView(webView)
        setContentView(root)

        loadCaptchaUrl(url)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)

        val url = intent.getStringExtra(EXTRA_URL)
        if (url.isNullOrBlank()) {
            finish()
            return
        }

        loadCaptchaUrl(url)
    }

    private fun loadCaptchaUrl(url: String) {
        loadGeneration += 1
        closeRequested = false
        closeChecks = 0
        captchaPageLoaded = false

        if (::webView.isInitialized) {
            webView.visibility = View.VISIBLE
            runCatching { webView.stopLoading() }
        }

        Log.i("VK TURN captcha WebView opening: $url")
        webView.loadUrl(url)
    }

    private fun scheduleCloseTextCheck() {
        if (closeRequested || closeChecks >= MAX_CLOSE_TEXT_CHECKS)
            return

        closeChecks += 1

        webView.postDelayed({
            if (!::webView.isInitialized || closeRequested || isFinishing || isDestroyed)
                return@postDelayed

            webView.evaluateJavascript(CLOSE_TEXT_SCRIPT) { result ->
                if (result == "true") {
                    Log.i("VK TURN captcha WebView detected close message")
                    requestClose()
                } else {
                    scheduleCloseTextCheck()
                }
            }
        }, CLOSE_TEXT_CHECK_INTERVAL)
    }

    private fun requestClose() {
        if (closeRequested)
            return

        closeRequested = true
        val generation = loadGeneration

        sendBroadcast(Intent(ACTION_SUBMITTED).setPackage(packageName))

        runOnUiThread {
            webView.visibility = View.INVISIBLE
            webView.postDelayed({
                if (!isFinishing && !isDestroyed && generation == loadGeneration) {
                    Log.i("VK TURN captcha WebView closing after successful submit")
                    finishAndRemoveTask()
                }
            }, CLOSE_DELAY)
        }
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (::webView.isInitialized) {
            runCatching { webView.stopLoading() }
            runCatching { root.removeView(webView) }
            webView.destroy()
        }

        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val ACTION_SUBMITTED = "com.github.kr328.clash.action.VK_TURN_CAPTCHA_SUBMITTED"
        private const val LOCAL_CAPTCHA_HOST = "127.0.0.1"
        private const val LOCAL_CAPTCHA_ORIGIN = "http://127.0.0.1:8765/"
        private const val LOCAL_CAPTCHA_ROOT_PATH = "/"
        private const val CLOSE_DELAY = 300L
        private const val CLOSE_TEXT_CHECK_INTERVAL = 1_000L
        private const val MAX_CLOSE_TEXT_CHECKS = 90
        private const val CLOSE_TEXT_SCRIPT =
            "(() => /can\\s+close|close\\s+this|you\\s+may\\s+close|" +
                    "\\u043c\\u043e\\u0436\\u043d\\u043e\\s+\\u0437\\u0430\\u043a\\u0440|" +
                    "\\u0437\\u0430\\u043a\\u0440\\u044b\\u0442\\u044c\\s+\\u0441\\u0442\\u0440\\u0430\\u043d/" +
                    ".test((document.body && document.body.innerText || '').toLowerCase()))();"
    }
}
