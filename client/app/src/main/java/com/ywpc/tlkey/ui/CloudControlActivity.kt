package com.ywpc.tlkey.ui

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.google.android.material.color.MaterialColors
import com.ywpc.tlkey.App
import com.ywpc.tlkey.R
import com.ywpc.tlkey.cloud.CloudCommand
import com.ywpc.tlkey.cloud.MqttConnectionState
import com.ywpc.tlkey.cloud.MqttCloudClient
import com.ywpc.tlkey.cloud.MqttEndpoint
import com.ywpc.tlkey.cloud.MqttEndpointResult
import com.ywpc.tlkey.cloud.MqttEndpoints
import com.ywpc.tlkey.cloud.MqttStatus
import com.ywpc.tlkey.cloud.MqttUnavailableReason
import com.ywpc.tlkey.databinding.ActivityCloudBinding
import com.ywpc.tlkey.vehicle.Vehicle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MQTT 云控：与官方 3.6.2 相同的 broker、topic 和指令，作为蓝牙控制的远程补充。
 * 连接参数来自 carStatus；缺失时自动刷新一次车辆信息。
 */
class CloudControlActivity : AppCompatActivity() {

    private lateinit var binding: ActivityCloudBinding
    private lateinit var app: App
    private lateinit var client: MqttCloudClient
    private var busy = false
    private var connected = false

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as App
        if (!app.loginManager.hasSession()) {
            openLogin()
            return
        }

        binding = ActivityCloudBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupEdgeToEdge(binding.root)

        client = MqttCloudClient()
        binding.btnBack.setOnClickListener { finish() }
        binding.btnCloudReconnect.setOnClickListener { reconnect() }
        binding.btnCloudRefresh.setOnClickListener { refreshVehicleInfo() }
        binding.btnCloudUnlock.setOnClickListener { send(CloudCommand.UNLOCK) }
        binding.btnCloudLock.setOnClickListener { send(CloudCommand.LOCK) }
        binding.btnCloudPowerOn.setOnClickListener { send(CloudCommand.POWER_ON) }
        binding.btnCloudPowerOff.setOnClickListener { send(CloudCommand.POWER_OFF) }
        binding.btnCloudFind.setOnClickListener { send(CloudCommand.FIND) }
        binding.btnCloudSeat.setOnClickListener { send(CloudCommand.SEAT) }
        binding.btnCloudMute.setOnClickListener { send(CloudCommand.MUTE) }
        binding.btnCloudUnmute.setOnClickListener { send(CloudCommand.UNMUTE) }

        observeClient()
        binding.tvCloudAcc.text = getString(R.string.cloud_acc_unknown)
        binding.tvCloudDefence.text = getString(R.string.cloud_defence_unknown)
        binding.tvCloudMute.text = getString(R.string.cloud_mute_unknown)
        binding.tvCloudUpdated.text = getString(R.string.cloud_no_message)

        val initial = resolve()
        renderVehicle()
        if (initial is MqttEndpointResult.Ready) {
            connect(initial.endpoint)
        } else {
            showUnavailable(initial)
            // carStatus 通常包含 mqHost/mqUsername；列表缓存可能还没有，自动刷新一次。
            refreshVehicleInfo(auto = true)
        }
    }

    override fun onDestroy() {
        if (::client.isInitialized) client.disconnect()
        super.onDestroy()
    }

    private fun observeClient() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { client.state.collect(::renderConnection) }
                launch { client.status.collect(::renderStatus) }
                launch { client.lastMessageAt.collect(::renderLastMessage) }
            }
        }
    }

    private fun resolve(): MqttEndpointResult {
        val vehicle = app.controller.cachedVehicle()
        val uid = vehicle?.userId?.takeIf { it.isNotBlank() }
            ?: app.authRepository.currentUid().orEmpty()
        return MqttEndpoints.resolve(vehicle, uid)
    }

    private fun renderVehicle() {
        val vehicle = app.controller.cachedVehicle()
        binding.tvCloudVehicle.text = vehicle?.let {
            "${it.name} · ${it.protocolLabel}"
        } ?: getString(R.string.cloud_vehicle_required)
    }

    private fun connect(endpoint: MqttEndpoint) {
        binding.tvCloudEndpoint.text = getString(
            R.string.cloud_endpoint,
            endpoint.host,
            endpoint.subscribeTopics.firstOrNull().orEmpty(),
        )
        lifecycleScope.launch(Dispatchers.IO) {
            client.connect(endpoint)
        }
    }

    private fun reconnect() {
        if (busy) return
        val result = resolve()
        if (result is MqttEndpointResult.Ready) {
            showResult(getString(R.string.cloud_connecting), isError = false)
            connect(result.endpoint)
        } else {
            showUnavailable(result)
        }
    }

    private fun refreshVehicleInfo(auto: Boolean = false) {
        if (busy) return
        busy = true
        updateButtons()
        if (!auto) showResult(getString(R.string.cloud_refreshing), isError = false)

        lifecycleScope.launch {
            try {
                app.loginManager.refreshVehicles()
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                showResult(t.message ?: getString(R.string.car_unknown), isError = true)
                busy = false
                updateButtons()
                return@launch
            }

            busy = false
            updateButtons()
            renderVehicle()
            val result = resolve()
            if (result is MqttEndpointResult.Ready) {
                connect(result.endpoint)
            } else {
                showUnavailable(result)
            }
        }
    }

    private fun send(command: CloudCommand) {
        if (busy || !connected) return
        busy = true
        updateButtons()
        showResult(getString(R.string.cloud_send_running, command.label), isError = false)
        val started = SystemClock.elapsedRealtime()

        lifecycleScope.launch {
            try {
                val result = client.send(command)
                val elapsed = (SystemClock.elapsedRealtime() - started).toInt()
                val message = if (result.ok) {
                    getString(R.string.cloud_send_done, result.message, elapsed)
                } else {
                    result.message
                }
                showResult(message, isError = !result.ok)
            } finally {
                busy = false
                updateButtons()
            }
        }
    }

    private fun renderConnection(state: MqttConnectionState) {
        connected = state is MqttConnectionState.Connected
        val text = when (state) {
            MqttConnectionState.Idle -> getString(R.string.cloud_disconnected)
            MqttConnectionState.Connecting -> getString(R.string.cloud_connecting)
            is MqttConnectionState.Connected -> getString(R.string.cloud_connected)
            is MqttConnectionState.Failed -> getString(R.string.cloud_connect_failed, state.message)
            is MqttConnectionState.Disconnected -> state.reason
                ?.let { getString(R.string.cloud_connect_failed, it) }
                ?: getString(R.string.cloud_disconnected)
        }
        binding.tvCloudConnection.text = text
        binding.tvCloudConnection.setTextColor(
            MaterialColors.getColor(
                binding.tvCloudConnection,
                if (state is MqttConnectionState.Failed) {
                    androidx.appcompat.R.attr.colorError
                } else {
                    androidx.appcompat.R.attr.colorPrimary
                },
            ),
        )
        updateButtons()
    }

    private fun renderStatus(status: MqttStatus) {
        binding.tvCloudAcc.text = when (status.accOn) {
            true -> getString(R.string.cloud_acc_on)
            false -> getString(R.string.cloud_acc_off)
            null -> getString(R.string.cloud_acc_unknown)
        }
        binding.tvCloudDefence.text = when (status.locked) {
            true -> getString(R.string.cloud_defence_locked)
            false -> getString(R.string.cloud_defence_unlocked)
            null -> getString(R.string.cloud_defence_unknown)
        }
        binding.tvCloudMute.text = when (status.muted) {
            true -> getString(R.string.cloud_mute_on)
            false -> getString(R.string.cloud_mute_off)
            null -> getString(R.string.cloud_mute_unknown)
        }
    }

    private fun renderLastMessage(timestamp: Long) {
        binding.tvCloudUpdated.text = if (timestamp <= 0L) {
            getString(R.string.cloud_no_message)
        } else {
            getString(R.string.cloud_last_message, timeFormat.format(Date(timestamp)))
        }
    }

    private fun showUnavailable(result: MqttEndpointResult) {
        val reason = (result as? MqttEndpointResult.Unavailable) ?: return
        val text = when (reason.reason) {
            MqttUnavailableReason.UNSUPPORTED_MODEL -> getString(R.string.cloud_reason_unsupported)
            MqttUnavailableReason.MISSING_IMEI -> getString(R.string.cloud_reason_no_imei)
            MqttUnavailableReason.MISSING_IMEI_GPS -> getString(R.string.cloud_reason_no_imei_gps)
            MqttUnavailableReason.MISSING_CREDENTIALS -> getString(R.string.cloud_reason_no_credentials)
            MqttUnavailableReason.MISSING_UID -> getString(R.string.cloud_reason_no_uid)
        }
        binding.tvCloudEndpoint.text = text
        showResult(text, isError = true)
        updateButtons()
    }

    private fun updateButtons() {
        val enabled = connected && !busy
        listOf(
            binding.btnCloudUnlock,
            binding.btnCloudLock,
            binding.btnCloudPowerOn,
            binding.btnCloudPowerOff,
            binding.btnCloudFind,
            binding.btnCloudSeat,
            binding.btnCloudMute,
            binding.btnCloudUnmute,
        ).forEach { it.isEnabled = enabled }
        binding.btnCloudReconnect.isEnabled = !busy
        binding.btnCloudRefresh.isEnabled = !busy
    }

    private fun showResult(message: String, isError: Boolean) {
        binding.tvCloudResult.text = message
        binding.tvCloudResult.isVisible = message.isNotBlank()
        binding.tvCloudResult.setTextColor(
            MaterialColors.getColor(
                binding.tvCloudResult,
                if (isError) {
                    androidx.appcompat.R.attr.colorError
                } else {
                    androidx.appcompat.R.attr.colorPrimary
                },
            ),
        )
    }

    private fun openLogin() {
        startActivity(
            Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
    }
}
