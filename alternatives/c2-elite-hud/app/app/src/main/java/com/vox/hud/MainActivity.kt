package com.vox.hud

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.os.PowerManager
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Log

/**
 * Full-screen HUD launcher activity.
 * Renders prototype-v3.html in a WebView, injects OBD data via JS.
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "MainActivity"
    }

    private var webView: WebView? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var pageLoaded = false

    private val dataReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val json = intent.getStringExtra(HudService.EXTRA_JSON)
            Log.d(TAG, "Data received, pageLoaded=$pageLoaded jsonLen=${json?.length}")
            if (!pageLoaded || json == null) return
            injectJs("updateData($json)")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Fullscreen
        window.apply {
            decorView.setBackgroundColor(Color.BLACK)
            decorView.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        // Wake lock
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
            "HUD:WakeLock"
        )

        // WebView fills the whole screen
        webView = WebView(this)
        setContentView(webView)
        setupWebView()

        // Start data service
        try {
            startService(Intent(this, HudService::class.java))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start service: ${e.message}")
        }

        // Listen for data
        registerReceiver(dataReceiver, IntentFilter(HudService.ACTION_UPDATE))
    }

    override fun onResume() {
        super.onResume()
        try { wakeLock?.acquire(10 * 60 * 1000L) } catch (_: Exception) {}
        hideSystemUI()
    }

    override fun onPause() {
        super.onPause()
        try { wakeLock?.release() } catch (_: Exception) {}
    }

    override fun onDestroy() {
        try { unregisterReceiver(dataReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    // ===== WebView =====

    private fun setupWebView() {
        webView?.settings?.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            loadWithOverviewMode = true
            useWideViewPort = true
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            allowFileAccess = true
        }

        webView?.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                pageLoaded = true
                Log.d(TAG, "Page loaded")
            }
        }
        webView?.setDefaultFocusHighlightEnabled(false)
        webView?.loadUrl("file:///android_asset/dashboard.html")
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        )
    }

    private fun injectJs(script: String) {
        runOnUiThread {
            try {
                webView?.evaluateJavascript(script, null)
            } catch (e: Exception) {
                Log.w(TAG, "JS injection error: ${e.message}")
            }
        }
    }

    // ===== Key events =====
    // 2026-08-18: 遥控器 (BLE GATT) 左右键 → 切换 UI 皮肤 (theme A/B)
    // 在 dispatch 阶段拦截，避免 WebView 消费 DPAD 导致 Activity 收不到
    override fun dispatchKeyEvent(event: KeyEvent?): Boolean {
        if (event != null && event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    injectJs("window.switchTheme && switchTheme(1)")
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    injectJs("window.switchTheme && switchTheme(-1)")
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_BACK -> true
            else -> super.onKeyDown(keyCode, event)
        }
    }
}
