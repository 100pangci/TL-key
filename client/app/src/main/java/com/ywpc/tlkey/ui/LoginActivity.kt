package com.ywpc.tlkey.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.ywpc.tlkey.App
import com.ywpc.tlkey.R
import com.ywpc.tlkey.auth.SmsRequestResult
import com.ywpc.tlkey.databinding.ActivityLoginBinding
import com.ywpc.tlkey.net.ApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class LoginActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLoginBinding
    private lateinit var app: App
    private var captcha: CaptchaDialog? = null
    private var cooldownJob: Job? = null
    private var cooldownGeneration = 0
    private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as App

        if (app.loginManager.hasSession()) {
            openMain()
            return
        }

        binding = ActivityLoginBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupEdgeToEdge(binding.root)
        setupActions()
    }

    private fun setupActions() {
        binding.btnGetCode.setOnClickListener {
            if (cooldownJob?.isActive == true) return@setOnClickListener
            if (!validatePhone()) return@setOnClickListener
            captcha?.dismiss()
            captcha = CaptchaDialog(this, object : CaptchaDialog.Listener {
                override fun onTicket(ticket: String, randstr: String) {
                    requestSms(ticket, randstr)
                }
            }).also { it.show() }
        }

        binding.btnManual.setOnClickListener {
            if (!validatePhone()) return@setOnClickListener
            binding.inputCode.requestFocus()
            Toast.makeText(this, R.string.open_official_app_hint, Toast.LENGTH_LONG).show()
        }

        binding.btnLogin.setOnClickListener { login() }
    }

    private fun requestSms(ticket: String, randstr: String) {
        val phone = phone()
        setLoading(true)
        lifecycleScope.launch {
            var result: SmsRequestResult? = null
            try {
                result = app.loginManager.requestSms(phone, ticket, randstr)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                showError(errorText(t, getString(R.string.code_request_failed)))
            } finally {
                setLoading(false)
            }
            val sent = result ?: return@launch
            showSendStatus(phone, sent)
            binding.inputCode.requestFocus()
            Toast.makeText(
                this@LoginActivity,
                getString(R.string.code_sent_toast, maskPhone(phone)),
                Toast.LENGTH_SHORT,
            ).show()
            startCooldown(SMS_COOLDOWN_SECONDS)
        }
    }

    private fun showSendStatus(phone: String, result: SmsRequestResult) {
        val send = result.send
        val serverBody = buildString {
            if (send.message.isNotBlank()) append(send.message)
            if (send.data.isNotBlank() && send.data != send.message) {
                if (isNotEmpty()) append(" · ")
                append(send.data)
            }
            if (isEmpty()) append(getString(R.string.server_empty))
        }
        val serverText = getString(
            R.string.server_meta,
            serverBody,
            send.code.ifBlank { "?" },
            send.httpStatus,
        )
        val sliderText = buildString {
            if (result.slider.captchaMsg.isNotBlank()) {
                append(result.slider.captchaMsg)
            } else {
                append(getString(R.string.slider_passed))
            }
            if (result.slider.expireIn > 0) {
                append(getString(R.string.slider_expire_suffix, result.slider.expireIn))
            }
            if (result.slider.captchaCode.isNotBlank() && result.slider.captchaCode != "0") {
                append("（captchaCode=${result.slider.captchaCode}）")
            }
        }
        binding.tvSendStatus.text = getString(
            R.string.code_sent_status,
            maskPhone(phone),
            serverText,
            sliderText,
        )
        binding.tvSendStatus.isVisible = true
    }

    private fun startCooldown(seconds: Int) {
        cooldownJob?.cancel()
        val generation = ++cooldownGeneration
        cooldownJob = lifecycleScope.launch {
            try {
                for (remaining in seconds downTo 1) {
                    binding.btnGetCode.text = getString(R.string.get_code_cooldown, remaining)
                    binding.btnGetCode.isEnabled = false
                    delay(1_000)
                }
            } finally {
                if (generation == cooldownGeneration) {
                    binding.btnGetCode.text = getString(R.string.get_code)
                    binding.btnGetCode.isEnabled = !loading
                }
            }
        }
    }

    private fun maskPhone(phone: String): String =
        if (phone.length == 11) {
            phone.substring(0, 3) + "****" + phone.substring(7)
        } else {
            phone
        }

    private fun login() {
        if (!validatePhone()) return
        val code = binding.inputCode.text?.toString()?.trim().orEmpty()
        if (code.length !in 4..8) {
            showError(getString(R.string.code_invalid))
            return
        }

        val phone = phone()
        setLoading(true)
        lifecycleScope.launch {
            try {
                app.loginManager.login(this@LoginActivity, phone, code)
                binding.tvError.isVisible = false
                openMain()
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                showError(errorText(t, "登录失败，请重试"))
            } finally {
                setLoading(false)
            }
        }
    }

    private fun validatePhone(): Boolean {
        val phone = phone()
        if (!PHONE_REGEX.matches(phone)) {
            showError(getString(R.string.phone_invalid))
            return false
        }
        binding.tvError.isVisible = false
        return true
    }

    private fun phone(): String = binding.inputPhone.text?.toString()?.trim().orEmpty()

    private fun setLoading(value: Boolean) {
        loading = value
        binding.progress.isVisible = value
        binding.btnGetCode.isEnabled = !value && cooldownJob?.isActive != true
        binding.btnManual.isEnabled = !value
        binding.btnLogin.isEnabled = !value
        binding.inputPhone.isEnabled = !value
        binding.inputCode.isEnabled = !value
    }

    private fun errorText(t: Throwable, fallback: String): String {
        val message = t.message?.takeIf { it.isNotBlank() } ?: fallback
        val code = (t as? ApiException)?.code
        return if (code.isNullOrBlank()) message else "$message（code=$code）"
    }

    private fun showError(message: String) {
        binding.tvSendStatus.isVisible = false
        binding.tvError.text = message
        binding.tvError.isVisible = true
    }

    private fun openMain() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP),
        )
        finish()
    }

    override fun onDestroy() {
        cooldownGeneration++
        cooldownJob = null
        captcha?.dismiss()
        captcha = null
        super.onDestroy()
    }

    private companion object {
        val PHONE_REGEX = Regex("^1\\d{10}$")
        const val SMS_COOLDOWN_SECONDS = 60
    }
}
