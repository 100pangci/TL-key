package com.ywpc.tlkey.ui

import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.isVisible
import com.ywpc.tlkey.App
import com.ywpc.tlkey.R
import com.ywpc.tlkey.auth.MacCode
import com.ywpc.tlkey.databinding.DialogCaptchaBinding
import org.json.JSONObject

/**
 * 只加载台铃官方滑块页，复用官方 JS 接口完成验证。
 * 不注入、不伪造 token，也不绕过腾讯验证。
 */
class CaptchaDialog(
    private val host: android.content.Context,
    private val listener: Listener,
) : Dialog(host) {

    interface Listener {
        fun onTicket(ticket: String, randstr: String)
    }

    private val binding = DialogCaptchaBinding.inflate(LayoutInflater.from(host))
    private val app = host.applicationContext as App
    private var delivered = false

    init {
        setContentView(binding.root)
        setCanceledOnTouchOutside(false)
        window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setDimAmount(0.45f)
            setGravity(Gravity.CENTER)
            val width = (host.resources.displayMetrics.widthPixels * 0.94f).toInt()
            setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        binding.btnClose.setOnClickListener { dismiss() }
        setupWebView()
    }

    private fun setupWebView() {
        WebView.setWebContentsDebuggingEnabled(false)
        with(binding.webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            // 与官方 WebDialog 的视口设置保持一致，避免滑块拿到异常的窗口尺寸。
            setBuiltInZoomControls(false)
            setDisplayZoomControls(false)
            setSupportZoom(false)
            defaultTextEncodingName = "UTF-8"
            useWideViewPort = true
            loadWithOverviewMode = true
            loadsImagesAutomatically = true
            textZoom = 100
        }
        binding.webView.setInitialScale(100)
        binding.webView.addJavascriptInterface(Bridge(), "tailgAppJsInterface")
        binding.webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                binding.progress.isVisible = newProgress < 100
            }
        }
        binding.webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                val url = request?.url ?: return true
                return !isTrustedHost(url.host)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                val host = runCatching { android.net.Uri.parse(url).host }.getOrNull()
                return !isTrustedHost(host)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                binding.progress.isVisible = false
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                if (request?.isForMainFrame == true) {
                    binding.progress.isVisible = false
                    showError(host.getString(R.string.captcha_subtitle))
                }
            }
        }
        binding.webView.loadUrl(CAPTCHA_URL)
    }

    private fun deliver(ticket: String, randstr: String) {
        binding.root.post {
            if (delivered) return@post
            delivered = true
            listener.onTicket(ticket, randstr)
            dismiss()
        }
    }

    private fun showError(message: String) {
        binding.root.post {
            binding.progress.isVisible = false
            binding.tvError.text = message
            binding.tvError.isVisible = true
        }
    }

    override fun dismiss() {
        runCatching {
            binding.webView.stopLoading()
            binding.webView.removeJavascriptInterface("tailgAppJsInterface")
            binding.webView.destroy()
        }
        super.dismiss()
    }

    private inner class Bridge {

        @JavascriptInterface
        fun getDeviceId(): String = MacCode.get(host)

        @JavascriptInterface
        fun getAppLoginToken(): String = app.tokenStore.token().orEmpty()

        @JavascriptInterface
        fun setToken(token: String?) {
            // 官方页面在未登录场景会回调空值；此处不需要保存任何东西。
        }

        @JavascriptInterface
        fun setSmsInfo(info: String?) {
            val json = runCatching { JSONObject(info.orEmpty()) }.getOrNull()
            val ticket = json?.optString("ticket").orEmpty()
            val randstr = json?.optString("randstr").orEmpty()
            if (ticket.isBlank() || randstr.isBlank()) {
                showError("滑块返回数据不完整，请重试")
                return
            }
            deliver(ticket, randstr)
        }

        @JavascriptInterface
        fun setError(error: String?) {
            showError(
                when (error?.trim()) {
                    null, "" -> "验证失败，请重试"
                    else -> "验证失败（$error）"
                },
            )
        }
    }

    private fun isTrustedHost(hostName: String?): Boolean {
        if (hostName.isNullOrBlank()) return false
        return TRUSTED_HOST_SUFFIXES.any { suffix ->
            hostName == suffix || hostName.endsWith(".$suffix")
        }
    }

    private companion object {
        const val CAPTCHA_URL = "https://www.tailgdd.com/document/appCode.html"

        // 官方 WebDialog 不拦截跳转；腾讯滑块会在 253/腾讯域名之间跳转。
        // 只放行台铃官网与腾讯验证码相关域名，其余导航仍然拦截。
        val TRUSTED_HOST_SUFFIXES = listOf(
            "tailgdd.com",
            "253.com",
            "qq.com",
            "qcloud.com",
            "gtimg.com",
            "tencentcs.com",
            "tencentrio.com",
            "tencent.com",
        )
    }
}
