package com.mbclaw.root.ui

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * 内置浏览器 - 在MBclaw内打开网页
 * 用于白嫖算力登录等需要浏览器交互的场景
 * 启动: Intent + URL extras
 */
class BrowserActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent?.getStringExtra("url") ?: "about:blank"
        val wv = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    setTitle(view.title ?: "MBclaw 浏览器")
                }
            }
            loadUrl(url)
        }
        setContentView(wv)
    }
}
