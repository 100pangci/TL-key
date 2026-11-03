package com.ywpc.tlkey.vehicle

import com.ywpc.tlkey.storage.SecureStorage

/**
 * 多车本地缓存。
 *
 * - `vehicles`：完整车辆数组（含 BLE 密码，Keystore 加密）；
 * - `activeVehicleId`：当前控制车辆。
 * 旧版单车缓存会在首次读取时自动迁移。
 */
class VehicleStore(private val secure: SecureStorage) {

    private val lock = Any()

    fun loadAll(): List<Vehicle> = synchronized(lock) {
        migrateLocked()
        readLocked()
    }

    fun activeId(): String? = synchronized(lock) {
        migrateLocked()
        val all = readLocked()
        val saved = secure.activeVehicleId
        when {
            saved != null && all.any { it.id == saved } -> saved
            else -> all.firstOrNull { it.isUsing }?.id ?: all.firstOrNull()?.id
        }
    }

    fun active(): Vehicle? = synchronized(lock) {
        migrateLocked()
        val all = readLocked()
        val saved = secure.activeVehicleId
        all.firstOrNull { it.id == saved }
            ?: all.firstOrNull { it.isUsing }
            ?: all.firstOrNull()
    }

    fun find(id: String): Vehicle? = synchronized(lock) {
        migrateLocked()
        readLocked().firstOrNull { it.id == id }
    }

    fun select(id: String): Vehicle? = synchronized(lock) {
        migrateLocked()
        val all = readLocked()
        val vehicle = all.firstOrNull { it.id == id } ?: return@synchronized null
        secure.activeVehicleId = id
        vehicle
    }

    /**
     * 用服务器列表刷新本地车辆，同时保留每辆车已缓存的 BLE 凭据。
     * 列表为空时不动缓存，避免接口异常把可用车辆清空。
     */
    fun mergeList(items: List<Vehicle>): List<Vehicle> = synchronized(lock) {
        migrateLocked()
        if (items.isEmpty()) return@synchronized readLocked()

        val current = readLocked().associateBy { it.id }
        val merged = items.map { item -> current[item.id]?.mergedWith(item) ?: item }
        val active = secure.activeVehicleId
            ?.takeIf { id -> merged.any { it.id == id } }
            ?: merged.firstOrNull { it.isUsing }?.id
            ?: merged.firstOrNull()?.id
        persistLocked(merged, active)
        merged
    }

    fun upsert(vehicle: Vehicle, makeActive: Boolean = false): Vehicle? = synchronized(lock) {
        migrateLocked()
        val all = readLocked().toMutableList()
        val index = all.indexOfFirst { it.id == vehicle.id }
        // 与已有记录合并：carStatus 有时不返回 btmac，若直接覆盖会丢掉刚扫描到的
        // Android 连接地址；userCarList 则本来就不带密码。
        val stored = if (index >= 0) all[index].mergedWith(vehicle) else vehicle
        if (index >= 0) {
            all[index] = stored
        } else {
            all.add(stored)
        }
        val currentActive = secure.activeVehicleId
            ?.takeIf { id -> all.any { it.id == id } }
        val active = when {
            makeActive -> stored.id
            currentActive != null -> currentActive
            stored.isUsing -> stored.id
            else -> all.first().id
        }
        persistLocked(all, active)
        stored
    }

    fun remove(id: String): List<Vehicle> = synchronized(lock) {
        migrateLocked()
        val remaining = readLocked().filterNot { it.id == id }
        val active = secure.activeVehicleId
            ?.takeIf { candidate -> remaining.any { it.id == candidate } }
            ?: remaining.firstOrNull()?.id
        persistLocked(remaining, active)
        remaining
    }

    fun clear() = synchronized(lock) {
        secure.vehiclesJson = null
        secure.activeVehicleId = null
        secure.vehicleJson = null
    }

    private fun migrateLocked() {
        if (secure.vehiclesJson != null) return
        val legacy = secure.vehicleJson ?: return
        val vehicle = runCatching { Vehicle.fromJson(legacy) }.getOrNull() ?: return
        persistLocked(listOf(vehicle), vehicle.id)
        secure.vehicleJson = null
    }

    private fun readLocked(): List<Vehicle> {
        val raw = secure.vehiclesJson ?: return emptyList()
        return Vehicle.fromJsonArray(raw)
    }

    private fun persistLocked(vehicles: List<Vehicle>, activeId: String?) {
        secure.vehiclesJson = Vehicle.toJsonArray(vehicles)
        secure.activeVehicleId = activeId
    }
}
