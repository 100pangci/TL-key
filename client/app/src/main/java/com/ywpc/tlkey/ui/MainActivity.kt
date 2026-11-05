package com.ywpc.tlkey.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.ywpc.tlkey.App
import com.ywpc.tlkey.R
import com.ywpc.tlkey.control.ControlResult
import com.ywpc.tlkey.control.LinkState
import com.ywpc.tlkey.control.isSuccess
import com.ywpc.tlkey.control.userMessage
import com.ywpc.tlkey.databinding.ActivityMainBinding
import com.ywpc.tlkey.protocol.ControlAction
import com.ywpc.tlkey.vehicle.Vehicle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var app: App
    private lateinit var pagerAdapter: VehiclePagerAdapter
    private var vehicles: List<Vehicle> = emptyList()
    private var busy = false
    private var activationJob: Job? = null
    private var pendingAction: ControlAction? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        val action = pendingAction
        pendingAction = null
        if (action != null) {
            if (hasConnectPermission()) {
                perform(action)
            } else {
                showEvent(getString(R.string.ble_permission_needed), isError = true)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        app = application as App
        if (!app.loginManager.hasSession()) {
            openLogin()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setupEdgeToEdge(binding.root)
        setupPager()
        setupActions()
        observeLinkState()
        renderVehicles()
        loadLastResult()
        requestInitialPermissions()
    }

    override fun onResume() {
        super.onResume()
        if (!::app.isInitialized || !::binding.isInitialized) return
        if (!app.loginManager.hasSession()) {
            openLogin()
            return
        }
        if (!busy) renderVehicles()
        loadLastResult()
    }

    private fun setupPager() {
        pagerAdapter = VehiclePagerAdapter(
            onAddClick = {
                startActivity(Intent(this, AddVehicleActivity::class.java))
            },
            onVehicleClick = { vehicle -> onVehicleClicked(vehicle) },
            onVehicleLongClick = { vehicle -> confirmRemoveVehicle(vehicle) },
        )
        binding.vehiclePager.adapter = pagerAdapter
        binding.vehiclePager.offscreenPageLimit = 1
        binding.vehiclePager.registerOnPageChangeCallback(
            object : ViewPager2.OnPageChangeCallback() {
                override fun onPageSelected(position: Int) {
                    handlePageSelected(position)
                }
            },
        )
    }

    private fun setupActions() {
        binding.btnUnlock.setOnClickListener { perform(ControlAction.UNLOCK) }
        binding.btnLock.setOnClickListener { perform(ControlAction.LOCK) }
        binding.btnPowerOn.setOnClickListener { perform(ControlAction.POWER_ON) }
        binding.btnPowerOff.setOnClickListener { perform(ControlAction.POWER_OFF) }
        binding.btnFind.setOnClickListener { perform(ControlAction.FIND) }

        // 主页只保留开锁/上锁、上电/断电、寻车；座桶、诊断、刷新、退出、ECU/云控/OTA 在二级菜单。
        binding.btnMore.setOnClickListener {
            startActivity(Intent(this, MoreActivity::class.java))
        }
    }

    private fun observeLinkState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                app.controller.linkState.collect(::renderLinkState)
            }
        }
    }

    /**
     * 从本机缓存重建分页。preferredId 用于刚完成绑定/激活后定位到对应车辆；
     * 否则回到当前 active 车辆；active 不存在时停在第一页。
     */
    private fun renderVehicles(preferredId: String? = null) {
        vehicles = app.loginManager.cachedVehicles()
        val activeId = app.controller.activeVehicleId()
        pagerAdapter.submit(vehicles, activeId)

        val target = preferredId?.let { id -> vehicles.indexOfFirst { it.id == id } }
            ?.takeIf { it >= 0 }
            ?: vehicles.indexOfFirst { it.id == activeId }.takeIf { it >= 0 }
            ?: 0
        if (binding.vehiclePager.currentItem != target) {
            binding.vehiclePager.setCurrentItem(target, false)
        }
        handlePageSelected(target)
    }

    private fun handlePageSelected(position: Int) {
        val vehicle = vehicles.getOrNull(position)
        renderDots(position)
        if (vehicle == null) {
            updateControls(null)
            return
        }

        if (app.controller.activeVehicleId() != vehicle.id) {
            val selected = app.controller.selectVehicle(vehicle.id)
            if (selected == null) {
                updateControls(null)
                showEvent(
                    getString(R.string.vehicle_activation_failed, vehicle.name),
                    isError = true,
                )
                return
            }
            pagerAdapter.updateActive(selected.id)
        }

        updateControls(vehicle)
        if (!vehicle.hasBleCredentials) {
            activateVehicle(vehicle)
        }
    }

    private fun onVehicleClicked(vehicle: Vehicle) {
        if (busy) return
        if (!vehicle.hasBleCredentials) {
            activateVehicle(vehicle)
        }
    }

    /** 第一次滑到某辆车：从服务器拉取完整 mac/passwordInfo 并缓存，之后可离线。 */
    private fun activateVehicle(vehicle: Vehicle) {
        if (busy || activationJob?.isActive == true) return
        setBusy(true)
        showEvent(getString(R.string.vehicle_state_switching), isError = false)
        activationJob = lifecycleScope.launch {
            try {
                val full = app.loginManager.activateVehicle(vehicle.id)
                renderVehicles(preferredId = full.id)
                showEvent(
                    getString(R.string.vehicle_activated, full.name.ifBlank { full.carType }),
                    isError = false,
                )
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                showEvent(
                    getString(
                        R.string.vehicle_activation_failed,
                        t.message ?: this@MainActivity.getString(R.string.car_unknown),
                    ),
                    isError = true,
                )
            } finally {
                setBusy(false)
            }
        }
    }

    private fun perform(action: ControlAction) {
        if (busy) return
        val vehicle = currentVehicle()
        if (vehicle == null) {
            showEvent(getString(R.string.add_page_selected), isError = true)
            return
        }
        if (!vehicle.hasBleCredentials) {
            activateVehicle(vehicle)
            return
        }
        if (!ensureConnectPermission(action)) return

        setBusy(true)
        lifecycleScope.launch {
            val result = app.controller.control(action)
            setBusy(false)
            showResult(result)
            loadLastResult()
        }
    }

    private fun confirmRemoveVehicle(vehicle: Vehicle) {
        if (busy) return
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vehicle_remove_title)
            .setMessage(R.string.vehicle_remove_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.vehicle_remove_confirm) { _, _ ->
                activationJob?.cancel()
                app.loginManager.removeLocal(vehicle.id)
                app.controller.closeSession()
                renderVehicles(preferredId = app.controller.activeVehicleId())
                showEvent(getString(R.string.vehicle_removed), isError = false)
            }
            .show()
    }

    private fun currentVehicle(): Vehicle? =
        vehicles.getOrNull(binding.vehiclePager.currentItem)

    private fun renderDots(selected: Int) {
        binding.pagerDots.removeAllViews()
        repeat(vehicles.size + 1) { index ->
            val dot = TextView(this).apply {
                text = "●"
                textSize = 10f
                setPadding(dp(3), 0, dp(3), 0)
                setTextColor(
                    MaterialColors.getColor(
                        this,
                        if (index == selected) {
                            androidx.appcompat.R.attr.colorPrimary
                        } else {
                            com.google.android.material.R.attr.colorOutlineVariant
                        },
                    ),
                )
            }
            binding.pagerDots.addView(dot)
        }
    }

    private fun renderLinkState(state: LinkState) {
        val textRes = when (state) {
            LinkState.IDLE -> R.string.state_idle
            LinkState.CONNECTING -> R.string.state_connecting
            LinkState.CLOUD -> R.string.state_cloud
            LinkState.READY -> R.string.state_ready
            LinkState.BUSY -> R.string.state_busy
        }
        val colorRes = when (state) {
            LinkState.IDLE -> R.color.status_neutral
            LinkState.CONNECTING, LinkState.CLOUD, LinkState.BUSY -> R.color.status_busy
            LinkState.READY -> R.color.status_ok
        }
        binding.tvBleState.setText(textRes)
        binding.tvBleState.backgroundTintList = ContextCompat.getColorStateList(this, colorRes)
    }

    private fun showResult(result: ControlResult) {
        val text = "${result.userMessage()} · ${result.trace.totalMs}ms"
        showEvent(text, isError = !result.isSuccess)
        if (result.isSuccess) {
            Toast.makeText(this, result.userMessage(), Toast.LENGTH_SHORT).show()
        }
    }

    private fun showEvent(message: String, isError: Boolean) {
        binding.tvEvent.text = message
        binding.tvEvent.isVisible = true
        binding.tvEvent.setTextColor(
            MaterialColors.getColor(
                binding.tvEvent,
                if (isError) {
                    androidx.appcompat.R.attr.colorError
                } else {
                    androidx.appcompat.R.attr.colorPrimary
                },
            ),
        )
    }

    private fun setBusy(value: Boolean) {
        busy = value
        updateControls(currentVehicle())
    }

    private fun updateControls(vehicle: Vehicle?) {
        val canControl = !busy && vehicle != null && vehicle.hasBleCredentials
        binding.btnUnlock.isEnabled = canControl
        binding.btnLock.isEnabled = canControl
        binding.btnPowerOn.isEnabled = canControl
        binding.btnPowerOff.isEnabled = canControl
        binding.btnFind.isEnabled = canControl
        binding.btnMore.isEnabled = !busy
        binding.vehiclePager.isUserInputEnabled = !busy
    }

    private fun loadLastResult() {
        lifecycleScope.launch {
            val last = app.preferences.lastResult() ?: return@launch
            binding.tvLastAction.text = last.first
            binding.tvLastTrace.text = last.second
        }
    }

    private fun requestInitialPermissions() {
        val missing = missingPermissions()
        if (missing.isNotEmpty()) permissionLauncher.launch(missing.toTypedArray())
    }

    private fun ensureConnectPermission(action: ControlAction): Boolean {
        if (hasConnectPermission()) return true
        pendingAction = action
        val missing = missingPermissions()
        if (missing.isEmpty()) {
            pendingAction = null
            showEvent(getString(R.string.ble_permission_needed), isError = true)
            return false
        }
        permissionLauncher.launch(missing.toTypedArray())
        return false
    }

    private fun missingPermissions(): List<String> {
        if (Build.VERSION.SDK_INT < 31) {
            val needsScan = app.controller.cachedVehicle()?.bleAddress == null
            return if (needsScan && !hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)) {
                listOf(Manifest.permission.ACCESS_FINE_LOCATION)
            } else {
                emptyList()
            }
        }
        return buildList {
            if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (app.controller.cachedVehicle()?.bleAddress == null &&
                !hasPermission(Manifest.permission.BLUETOOTH_SCAN)
            ) {
                add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }
    }

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 || hasPermission(Manifest.permission.BLUETOOTH_CONNECT)

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun openLogin() {
        startActivity(
            Intent(this, LoginActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )
        finish()
    }
}
