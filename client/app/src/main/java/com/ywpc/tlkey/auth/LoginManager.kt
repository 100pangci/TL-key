package com.ywpc.tlkey.auth

import android.content.Context
import com.ywpc.tlkey.vehicle.Vehicle
import com.ywpc.tlkey.vehicle.VehicleRepository

class LoginManager(
    private val authRepository: AuthRepository,
    private val vehicleRepository: VehicleRepository,
) {

    suspend fun requestSms(phone: String, ticket: String, randstr: String): SmsRequestResult {
        return authRepository.requestSms(phone, ticket, randstr)
    }

    /**
     * 登录成功后拉取车辆列表与当前车辆，把 uid + 车辆密码一起缓存。
     * 车辆接口失败不应让已经成功的登录作废；返回 null 时主界面会提示刷新/添加车辆。
     */
    suspend fun login(context: Context, phone: String, smsCode: String): Vehicle? {
        val login = authRepository.login(context, phone, smsCode)
        runCatching { vehicleRepository.refresh(login.token) }
        val current = vehicleRepository.cached()
        authRepository.ensureUid(current?.userId?.takeIf { it.isNotBlank() } ?: login.uid)
        return current
    }

    /** 手动刷新：重新拉列表和当前车辆，但保留用户在本机选择的车辆。 */
    suspend fun refreshVehicle(): Vehicle? {
        val token = authRepository.getValidToken()
        vehicleRepository.refresh(token)
        val current = vehicleRepository.cached()
        current?.userId?.takeIf { it.isNotBlank() }?.let(authRepository::ensureUid)
        return current
    }

    suspend fun refreshVehicles(): List<Vehicle> {
        val token = authRepository.getValidToken()
        val vehicles = vehicleRepository.refresh(token)
        vehicles.firstOrNull()?.userId?.takeIf { it.isNotBlank() }?.let(authRepository::ensureUid)
        return vehicles
    }

    /** 切到某辆车；已缓存 BLE 凭据时完全离线。 */
    suspend fun activateVehicle(carId: String): Vehicle {
        val token = authRepository.getValidToken()
        val vehicle = vehicleRepository.switchTo(token, carId)
        vehicle.userId.takeIf { it.isNotBlank() }?.let(authRepository::ensureUid)
        return vehicle
    }

    fun cachedVehicle(): Vehicle? = vehicleRepository.cached()

    fun cachedVehicles(): List<Vehicle> = vehicleRepository.cachedAll()

    fun selectLocal(carId: String): Vehicle? = vehicleRepository.select(carId)

    fun removeLocal(carId: String): List<Vehicle> = vehicleRepository.removeLocal(carId)

    fun hasSession(): Boolean = authRepository.hasToken()

    fun logout() {
        authRepository.logout()
        vehicleRepository.clear()
    }
}
