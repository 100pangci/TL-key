package com.ywpc.tlkey.vehicle

import com.ywpc.tlkey.net.ApiException
import kotlinx.coroutines.delay

/**
 * 多车仓库。
 *
 * 切换策略：
 * 1. 本机已有该车的 mac + 密码 → 只改本地 active id，完全离线；
 * 2. 没有完整数据 → 调 `changeUsingCar` 再 `carStatus` 拉取并缓存。
 */
class VehicleRepository(
    private val api: VehicleApi,
    private val store: VehicleStore,
) {

    fun cachedAll(): List<Vehicle> = store.loadAll()

    fun cached(): Vehicle? = store.active()

    fun activeId(): String? = store.activeId()

    fun find(id: String): Vehicle? = store.find(id)

    /** 登录/刷新后强制使用服务器当前车辆，并缓存完整 BLE 信息。 */
    suspend fun fetchCurrent(token: String): Vehicle {
        val vehicle = api.carStatus(token)
        store.upsert(vehicle, makeActive = true)
        return vehicle
    }

    /** 只拉车辆列表，不改变当前选择。 */
    suspend fun list(token: String): List<Vehicle> {
        val items = api.userCarList(token)
        return store.mergeList(items)
    }

    /**
     * 刷新列表 + 当前车辆详情。`carStatus` 失败（例如账号下暂时没有车）不影响列表结果。
     */
    suspend fun refresh(token: String): List<Vehicle> {
        val items = api.userCarList(token)
        val merged = store.mergeList(items)
        val current = runCatching { api.carStatus(token) }.getOrNull()
        if (current != null) {
            val hasActive = store.activeId() != null
            store.upsert(current, makeActive = !hasActive || current.isUsing)
        }
        return store.loadAll().ifEmpty { merged }
    }

    /**
     * 切到指定车辆。已缓存完整 BLE 信息的车辆不联网。
     *
     * @throws ApiException 切换后服务器仍未返回目标车辆时。
     */
    suspend fun switchTo(token: String, carId: String): Vehicle {
        val local = store.find(carId) ?: throw ApiException("车辆不在本机列表中")
        if (local.hasBleCredentials) {
            store.select(carId)
            return local
        }

        api.changeUsingCar(token, carId)
        // changeUsingCar 与 carStatus 之间可能有短暂延迟，重试一次。
        repeat(2) { attempt ->
            if (attempt > 0) delay(500)
            val full = api.carStatus(token)
            if (full.id == carId) {
                store.upsert(full, makeActive = true)
                return full
            }
        }
        throw ApiException("切换车辆失败：服务器未返回目标车辆")
    }

    /**
     * 强制从服务器拉取某辆车的完整信息（忽略本地缓存），用于刚绑定完的车辆。
     */
    suspend fun fetchFull(token: String, carId: String): Vehicle {
        api.changeUsingCar(token, carId)
        repeat(2) { attempt ->
            if (attempt > 0) delay(500)
            val full = api.carStatus(token)
            if (full.id == carId) {
                store.upsert(full, makeActive = true)
                return full
            }
        }
        throw ApiException("服务器未返回刚绑定的车辆")
    }

    /** 纯本地切换，不联网。 */
    fun select(carId: String): Vehicle? = store.select(carId)

    fun save(vehicle: Vehicle, makeActive: Boolean = false): Vehicle? =
        store.upsert(vehicle, makeActive)

    fun removeLocal(carId: String): List<Vehicle> = store.remove(carId)

    fun clear() = store.clear()
}
