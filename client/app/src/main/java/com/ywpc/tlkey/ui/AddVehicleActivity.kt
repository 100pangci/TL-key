package com.ywpc.tlkey.ui

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ywpc.tlkey.App
import com.ywpc.tlkey.R
import com.ywpc.tlkey.ble.BindingDevice
import com.ywpc.tlkey.databinding.ActivityAddVehicleBinding
import com.ywpc.tlkey.databinding.ItemBindingDeviceBinding
import com.ywpc.tlkey.vehicle.BikeBind
import com.ywpc.tlkey.vehicle.BindException
import com.ywpc.tlkey.vehicle.BindOutcome
import com.ywpc.tlkey.vehicle.CarInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 从零添加车辆：车架号查询 → 服务器绑定 → 搜索车辆 BLE → 官方 BLE_RESET 写密码。
 *
 * 完全复用 [com.ywpc.tlkey.vehicle.BindRepository] 的逆向流程；UI 只负责步骤编排与展示。
 */
@SuppressLint("MissingPermission")
class AddVehicleActivity : AppCompatActivity() {

    private lateinit var binding: ActivityAddVehicleBinding
    private lateinit var app: App
    private var carInfo: CarInfo? = null
    private var bikeBind: BikeBind? = null
    private var bindSupported = false
    private var busy = false
    private var pendingBleAction: (() -> Unit)? = null
    private var scanJob: Job? = null
    private var bindingJob: Job? = null
    private val foundDevices = LinkedHashMap<String, BindingDevice>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val action = pendingBleAction
        pendingBleAction = null
        if (action == null) return@registerForActivityResult
        if (hasBlePermissions()) {
            action()
        } else {
            showStatus(getString(R.string.bind_permission_needed), isError = true)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as App
        binding = ActivityAddVehicleBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupEdgeToEdge(binding.root)
        binding.btnBack.setOnClickListener { finish() }
        binding.btnLookup.setOnClickListener { lookupVehicle() }
        binding.btnStartBind.setOnClickListener { startBind() }
        binding.btnRescan.setOnClickListener { requestBlePermissionThenScan() }
        binding.inputCode.setOnEditorActionListener { _, _, _ ->
            lookupVehicle()
            true
        }
        onBackPressedDispatcher.addCallback(
            this,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    if (busy) {
                        showStatus(getString(R.string.bind_in_progress_hint), isError = false)
                    } else {
                        finish()
                    }
                }
            },
        )
        setBusy(false)
    }

    private fun lookupVehicle() {
        if (busy) return
        val code = binding.inputCode.text?.toString()?.trim().orEmpty()
        if (code.isBlank()) {
            binding.inputLayoutCode.error = getString(R.string.vehicle_code_hint)
            return
        }
        binding.inputLayoutCode.error = null
        setBusy(true)
        showStatus(getString(R.string.lookup_running), isError = false)
        lifecycleScope.launch {
            try {
                val info = app.bindRepository.lookup(code)
                carInfo = info
                bindSupported = !info.needsBleBind || supportsBleBind(info.modelType)
                renderCarInfo(info)
                if (bindSupported) {
                    showStatus(
                        getString(
                            R.string.vehicle_found,
                            info.carName.ifBlank { info.carType },
                        ),
                        isError = false,
                    )
                } else {
                    showStatus(getString(R.string.bind_unsupported), isError = true)
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                showStatus(describe(t), isError = true)
            } finally {
                setBusy(false)
            }
        }
    }

    private fun renderCarInfo(info: CarInfo) {
        binding.cardVehicleInfo.isVisible = true
        binding.tvCarTitle.text = info.carName.ifBlank { info.carType }
        binding.tvCarMeta.text = getString(
            R.string.vehicle_info_meta,
            info.carType.ifBlank { "-" },
            info.frame.ifBlank { "-" },
            info.modelType,
        )
        binding.tvCarStatus.text = getString(
            R.string.vehicle_info_status,
            info.isBluetooth,
            info.isTelligence,
            info.status.ifBlank { "-" },
        )
    }

    private fun startBind() {
        val info = carInfo ?: return
        if (busy) return
        if (!bindSupported) {
            showStatus(getString(R.string.bind_unsupported), isError = true)
            return
        }
        setBusy(true)
        showStatus(getString(R.string.bind_server_running), isError = false)
        lifecycleScope.launch {
            var needsScan = false
            try {
                val bind = app.bindRepository.bindServer(info)
                bikeBind = bind
                when {
                    // 78 帧：官方没有可达的 BLE 密码写入调用，服务器绑定后直接验证 token 登录。
                    info.modelType == 1 || info.modelType == 2 ->
                        showSuccess(
                            app.bindRepository.bindLegacy(info, bind) { step ->
                                showStatus(step, isError = false)
                            },
                        )

                    info.needsBleBind -> needsScan = true
                    else -> showSuccess(app.bindRepository.bindWithoutBle(info, bind))
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                showStatus(describe(t), isError = true)
            } finally {
                setBusy(false)
                if (needsScan) requestBlePermissionThenScan()
            }
        }
    }

    private fun requestBlePermissionThenScan() {
        if (busy) return
        if (bikeBind == null) {
            showStatus(getString(R.string.start_bind), isError = true)
            return
        }
        if (hasBlePermissions()) {
            startScan()
            return
        }
        pendingBleAction = { startScan() }
        val permissions = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
            )
        } else {
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        permissionLauncher.launch(permissions)
    }

    private fun startScan() {
        if (busy || bikeBind == null) return
        if (!isBluetoothEnabled()) {
            showStatus(getString(R.string.bind_bluetooth_disabled), isError = true)
            return
        }
        setBusy(true)
        binding.cardScan.isVisible = true
        binding.deviceList.removeAllViews()
        foundDevices.clear()
        showStatus(getString(R.string.scan_running), isError = false)
        scanJob = lifecycleScope.launch {
            try {
                val devices = app.bindRepository.scanDevices { device ->
                    // 扫到一个就展示一个，不必等 30 秒扫描窗口结束。
                    foundDevices[device.address] = device
                    renderDevices(foundDevices.values.toList())
                    showStatus(
                        getString(R.string.scan_found_so_far, foundDevices.size),
                        isError = false,
                    )
                }
                if (devices.isEmpty()) {
                    showStatus(getString(R.string.scan_empty), isError = true)
                } else {
                    renderDevices(devices)
                    showStatus(
                        getString(R.string.scan_devices_hint, devices.size),
                        isError = false,
                    )
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                showStatus(describe(t), isError = true)
            } finally {
                scanJob = null
                setBusy(false)
            }
        }
    }

    private fun renderDevices(devices: List<BindingDevice>) {
        binding.deviceList.removeAllViews()
        devices.forEach { device ->
            val row = ItemBindingDeviceBinding.inflate(
                layoutInflater,
                binding.deviceList,
                false,
            )
            row.tvDeviceName.text = device.name
            row.tvDeviceMeta.text = getString(
                R.string.device_option,
                device.address,
                device.rssi,
            )
            row.root.setOnClickListener { selectDevice(device) }
            binding.deviceList.addView(row.root)
        }
    }

    /** 扫描过程中点选设备：先取消扫描，等它释放回调后再开始配对。 */
    private fun selectDevice(device: BindingDevice) {
        if (bindingJob?.isActive == true) return
        val job = scanJob
        if (job != null && job.isActive) {
            job.cancel()
            lifecycleScope.launch {
                job.join()
                startBleBind(device)
            }
            return
        }
        if (!busy) startBleBind(device)
    }

    private fun startBleBind(device: BindingDevice) {
        val info = carInfo ?: return
        val bind = bikeBind ?: return
        if (busy || bindingJob?.isActive == true) return
        setBusy(true)
        binding.cardScan.isVisible = false
        showStatus(getString(R.string.bind_running), isError = false)
        bindingJob = lifecycleScope.launch {
            try {
                val outcome = app.bindRepository.completeBleBind(info, bind, device) { step ->
                    showStatus(step, isError = false)
                }
                showSuccess(outcome)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                binding.cardScan.isVisible = true
                showStatus(describe(t), isError = true)
            } finally {
                bindingJob = null
                setBusy(false)
            }
        }
    }

    private fun showSuccess(outcome: BindOutcome) {
        setBusy(false)
        val name = outcome.vehicle.name.ifBlank {
            outcome.vehicle.carType.ifBlank { getString(R.string.car_unknown) }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.bind_success, name))
            .setMessage(
                outcome.warning?.let { getString(R.string.bind_success_warning, it) }
                    ?: getString(R.string.bind_success_message),
            )
            .setCancelable(false)
            .setPositiveButton(R.string.confirm) { _, _ ->
                setResult(RESULT_OK)
                finish()
            }
            .show()
    }

    private fun setBusy(value: Boolean) {
        busy = value
        binding.progress.isVisible = value
        binding.inputCode.isEnabled = !value
        binding.btnLookup.isEnabled = !value
        binding.btnStartBind.isEnabled = !value && carInfo != null && bindSupported
        binding.btnRescan.isEnabled = !value
        binding.btnBack.isEnabled = !value
    }

    private fun showStatus(message: String, isError: Boolean) {
        binding.tvStatus.text = message
        binding.tvStatus.isVisible = message.isNotBlank()
        binding.tvStatus.setTextColor(
            MaterialColors.getColor(
                binding.tvStatus,
                if (isError) {
                    androidx.appcompat.R.attr.colorError
                } else {
                    androidx.appcompat.R.attr.colorPrimary
                },
            ),
        )
    }

    private fun describe(t: Throwable): String {
        val raw = t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName
        val detail = when (raw.trim()) {
            "-2" -> getString(R.string.bind_error_self)
            "-1" -> getString(R.string.bind_error_other)
            "-4" -> getString(R.string.bind_error_limited)
            else -> raw
        }
        return if (t is BindException) {
            getString(R.string.bind_failed, "${t.step}：$detail")
        } else {
            getString(R.string.bind_failed, detail)
        }
    }

    private fun isBluetoothEnabled(): Boolean {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        return manager?.adapter?.isEnabled == true
    }

    private fun hasBlePermissions(): Boolean = if (Build.VERSION.SDK_INT >= 31) {
        hasPermission(Manifest.permission.BLUETOOTH_CONNECT) &&
            hasPermission(Manifest.permission.BLUETOOTH_SCAN)
    } else {
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    /** 服务器 modelType=0 时允许先扫描，真正的协议由广播名反推；78 帧不需要扫描。 */
    private fun supportsBleBind(modelType: Int): Boolean =
        modelType == 0 || modelType in SUPPORTED_BLE_TYPES

    private companion object {
        val SUPPORTED_BLE_TYPES =
            setOf(1, 2, 3, 8, 10, 14, 283, 401, 928, 1501, 1601, 1701, 2103, 2201)
    }
}
