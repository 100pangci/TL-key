package com.ywpc.tlkey.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.ywpc.tlkey.R
import com.ywpc.tlkey.databinding.ItemAddVehiclePageBinding
import com.ywpc.tlkey.databinding.ItemVehiclePageBinding
import com.ywpc.tlkey.protocol.ProtocolSelector
import com.ywpc.tlkey.vehicle.Vehicle

/**
 * 主界面横向分页：前 N 页是车辆卡片，最后一页固定是“添加车辆”。
 * 车辆来自本机加密缓存，所以分页不依赖网络。
 */
class VehiclePagerAdapter(
    private val onAddClick: () -> Unit,
    private val onVehicleClick: (Vehicle) -> Unit,
    private val onVehicleLongClick: (Vehicle) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private var vehicles: List<Vehicle> = emptyList()
    private var activeId: String? = null

    fun submit(list: List<Vehicle>, active: String?) {
        vehicles = list
        activeId = active
        notifyDataSetChanged()
    }

    fun updateActive(carId: String?) {
        if (activeId == carId) return
        activeId = carId
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = vehicles.size + 1

    override fun getItemViewType(position: Int): Int =
        if (position < vehicles.size) TYPE_VEHICLE else TYPE_ADD

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_VEHICLE) {
            VehicleHolder(ItemVehiclePageBinding.inflate(inflater, parent, false))
        } else {
            AddHolder(ItemAddVehiclePageBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is VehicleHolder -> holder.bind(vehicles[position])
            is AddHolder -> holder.bind()
        }
    }

    private inner class VehicleHolder(
        private val binding: ItemVehiclePageBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(vehicle: Vehicle) {
            val context = binding.root.context
            binding.tvPageName.text = vehicle.name.ifBlank {
                vehicle.carType.ifBlank { context.getString(R.string.car_unknown) }
            }
            binding.tvPageMeta.text = buildString {
                if (vehicle.carType.isNotBlank()) append(vehicle.carType).append(" · ")
                append(vehicle.protocolLabel)
                append(" · modelType ").append(vehicle.modelType)
            }
            binding.tvPageActive.isVisible = vehicle.id == activeId
            binding.tvPageHint.text = context.getString(R.string.vehicle_page_long_press_hint)

            val unsupported = ProtocolSelector.create(vehicle.modelType) == null
            val stateText = when {
                unsupported -> R.string.vehicle_state_unsupported
                vehicle.bleAddress == null -> R.string.vehicle_state_no_ble
                !vehicle.hasBleCredentials -> R.string.vehicle_state_needs_network
                else -> R.string.vehicle_state_cached
            }
            binding.tvPageState.setText(stateText)

            binding.root.setOnClickListener { onVehicleClick(vehicle) }
            binding.root.setOnLongClickListener {
                onVehicleLongClick(vehicle)
                true
            }
        }
    }

    private inner class AddHolder(
        private val binding: ItemAddVehiclePageBinding,
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind() {
            binding.root.setOnClickListener { onAddClick() }
        }
    }

    private companion object {
        const val TYPE_VEHICLE = 0
        const val TYPE_ADD = 1
    }
}
