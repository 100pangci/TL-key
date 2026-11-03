package com.ywpc.tlkey.vehicle

import android.content.Context
import com.ywpc.tlkey.auth.AuthRepository
import com.ywpc.tlkey.ble.BindingDevice
import com.ywpc.tlkey.ble.BindingNames
import com.ywpc.tlkey.ble.BleResetSession
import com.ywpc.tlkey.ble.BleScanner
import com.ywpc.tlkey.ble.BleSession
import com.ywpc.tlkey.ble.QgjLink
import com.ywpc.tlkey.ble.QgjSession
import com.ywpc.tlkey.ble.TraceSink
import com.ywpc.tlkey.ble.VehicleLink
import com.ywpc.tlkey.protocol.ProtocolFamily
import com.ywpc.tlkey.protocol.ProtocolSelector
import com.ywpc.tlkey.protocol.QgjCodec
import kotlinx.coroutines.CancellationException
import org.json.JSONArray
import org.json.JSONObject

class BindException(val step: String, message: String) : Exception(message)

data class BindOutcome(
    val vehicle: Vehicle,
    val activation: EcuGet?,
    /** 绑定成功但存在非致命问题（未激活、密码验证失败等）。 */
    val warning: String?,
)

/**
 * 从零配对/绑定的编排层，严格按官方 3.6.2 正常流程：
 *
 * getCarInfo → v3/vehicle/bind → BLE_RESET 读 2a23 → bikeBindAgain/bindAgain
 * → 850A4A10 写密码 → updateCarInfo → activeGet → BLE_AUTH 登录验证。
 *
 * QGJ（8/283）走独立的 Kuyi 通道：先读身份 MAC / GPS IMEI，再回写服务器，
 * 最后通过 ECU_BIND 写密码。旧版 78 帧绑定尚未完整逆向，明确报错而不是猜测。
 */
class BindRepository(
    private val context: Context,
    private val authRepository: AuthRepository,
    private val bindApi: BindApi,
    private val vehicleRepository: VehicleRepository,
    private val scanner: BleScanner,
) {

    suspend fun lookup(barCode: String): CarInfo {
        val code = barCode.trim()
        if (code.isBlank()) throw BindException("查询车辆", "请输入车架号或二维码内容")
        val token = authRepository.getValidToken()
        return try {
            bindApi.getCarInfo(token, code)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            throw BindException("查询车辆", t.describe())
        }
    }

    /** 服务器侧绑车，拿到 passwordInfo。 */
    suspend fun bindServer(carInfo: CarInfo): BikeBind {
        carInfo.precheckError(null)?.let { throw BindException("检查车辆", it) }
        val token = authRepository.getValidToken()
        return try {
            bindApi.bind(token, carInfo)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            throw BindException("服务器绑定", t.describe())
        }
    }

    suspend fun scanDevices(
        onDevice: ((BindingDevice) -> Unit)? = null,
    ): List<BindingDevice> {
        return try {
            scanner.scanForBinding(onDevice = onDevice)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            throw BindException("搜索车辆蓝牙", t.describe())
        }
    }

    /** 非智能车/无蓝牙车：只做服务器绑定。 */
    suspend fun bindWithoutBle(carInfo: CarInfo, bikeBind: BikeBind): BindOutcome {
        val uid = authRepository.currentUid().orEmpty()
        val local = bikeBind.toVehicle(
            uid = uid,
            fallbackFrame = carInfo.frame,
            fallbackName = carInfo.carName,
        )
        vehicleRepository.save(local, makeActive = true)
        runCatching { vehicleRepository.refresh(authRepository.getValidToken()) }
        return BindOutcome(
            vehicle = vehicleRepository.find(local.id) ?: local,
            activation = null,
            warning = "该车未开放蓝牙绑定通道，只能完成服务器绑定，无法用蓝牙控车",
        )
    }

    /**
     * 78 帧（modelType 1/2）绑定。
     *
     * 官方 3.6.2 与 3.5.6 中 `TailgBleCmd.exeBindCar` 没有任何可达调用点，
     * 78 帧控车只依赖车端 token、不需要密码登录（[com.ywpc.tlkey.protocol.Protocol78.requiresLogin] = false），
     * 因此这里只做服务器绑定、保存车辆并做一次 token 登录验证，不猜测 AES 绑定命令的参数语义。
     */
    suspend fun bindLegacy(
        carInfo: CarInfo,
        bikeBind: BikeBind,
        onStep: (String) -> Unit,
    ): BindOutcome {
        val uid = authRepository.currentUid()?.takeIf { it.isNotBlank() }
            ?: throw BindException("准备绑定", "缺少账号 uid，请重新登录")
        val modelType = carInfo.modelType.takeIf { it != 0 } ?: bikeBind.modelType
        val local = bikeBind.toVehicle(
            uid = uid,
            fallbackFrame = carInfo.frame,
            fallbackName = carInfo.carName,
        ).copy(modelType = modelType)
        vehicleRepository.save(local, makeActive = true)

        val token = authRepository.getValidToken()
        onStep("刷新车辆信息…")
        val vehicle = runCatching { vehicleRepository.fetchFull(token, carInfo.carId) }
            .getOrElse { local }

        onStep("验证车辆登录…")
        val verifyWarning = verifyLogin(vehicle, uid)
        return BindOutcome(
            vehicle = vehicle,
            activation = null,
            warning = verifyWarning,
        )
    }

    /**
     * BLE 绑定全流程。调用方负责先完成 [bindServer] 并让用户选中要绑定的车辆。
     */
    suspend fun completeBleBind(
        carInfo: CarInfo,
        bikeBind: BikeBind,
        device: BindingDevice,
        onStep: (String) -> Unit,
    ): BindOutcome {
        val uid = authRepository.currentUid()?.takeIf { it.isNotBlank() }
            ?: throw BindException("准备绑定", "缺少账号 uid，请重新登录")
        val modelType = BindingNames.modelTypeFor(device.name)
            ?: throw BindException("识别车型", "无法从广播名识别车型：${device.name}")
        if (modelType == 8 || modelType == 283) {
            return completeQgjBind(carInfo, bikeBind, device, uid, modelType, onStep)
        }
        if (modelType !in SUPPORTED_BLE_TYPES) {
            throw BindException("识别车型", "暂不支持 modelType=$modelType 的蓝牙绑定")
        }
        if (bikeBind.passwordMain.isBlank() || bikeBind.passwordChildren.isEmpty()) {
            throw BindException("服务器绑定", "服务器未下发 BLE 密码，无法继续")
        }

        val session = BleResetSession(
            context = context,
            address = device.address,
            modelType = modelType,
            mainPassword = bikeBind.passwordMain,
            childPassword = bikeBind.passwordChildren.firstOrNull(),
            manufacturerData = device.manufacturerData,
        )
        val identity = try {
            onStep("正在连接车辆并读取身份…")
            session.connectAndReadIdentity()
        } catch (t: CancellationException) {
            session.close()
            throw t
        } catch (t: Throwable) {
            session.close()
            throw BindException("读取车辆身份", t.describe())
        }

        try {
            onStep("正在回写车辆 MAC…")
            val body = buildBindAgainBody(carInfo, bikeBind, identity, uid, device.name, modelType)
            val token = authRepository.getValidToken()
            try {
                when (modelType) {
                    10, 14 -> bindApi.bikeBindAgainC39(token, body)
                    401, 928, 1501, 1601, 1701, 2103, 2201 -> bindApi.bindAgain(token, body)
                    else -> bindApi.bikeBindAgain(token, body)
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                throw BindException("回写车辆 MAC", t.describe())
            }

            onStep("正在写入车辆蓝牙密码…")
            val resetOk = try {
                session.resetPassword()
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                throw BindException("写入车辆密码", t.describe())
            }
            if (!resetOk) {
                throw BindException("写入车辆密码", "车辆拒绝写入密码（8503B510 状态非 01）")
            }
        } finally {
            session.close()
        }

        onStep("服务器确认绑定…")
        val token = authRepository.getValidToken()
        val updated = runCatching { bindApi.updateCarInfo(token, carInfo.carId, "1") }.getOrNull()
        // 官方用 updateQgjCarInfo 返回的 frame 调 activeGet；缺失时才回退 getCarInfo 的 frame。
        val frame = updated?.optString("frame")?.takeIf { it.isNotBlank() } ?: carInfo.frame
        val activation = runCatching { bindApi.activeGet(token, frame) }.getOrNull()

        val local = bikeBind.toVehicle(
            uid = uid,
            btName = device.name,
            btMac = device.address,
            fallbackMac = identity.systemIdHex,
            fallbackFrame = carInfo.frame,
            fallbackName = carInfo.carName,
        ).copy(modelType = modelType)
        vehicleRepository.save(local, makeActive = true)

        onStep("刷新车辆信息…")
        val vehicle = runCatching { vehicleRepository.fetchFull(token, carInfo.carId) }
            .getOrElse { local }

        onStep("验证车辆登录…")
        val verifyWarning = verifyLogin(vehicle, uid)
        val activationWarning = if (activation != null && activation.isActivate == 0) {
            "车辆尚未激活，控车可能受限，请先在官方 App 完成激活"
        } else {
            null
        }
        return BindOutcome(
            vehicle = vehicle,
            activation = activation,
            warning = listOfNotNull(activationWarning, verifyWarning).joinToString("；").ifBlank { null },
        )
    }

    /**
     * QGJ 绑定。官方顺序（BindCarBleSearchFragment）：
     * 连接车辆 → 读取身份 MAC（Harmony 读 2a23；283 还要读 GPS IMEI/code）
     * → bikeBindAgain/bindAgain → BLE ECU_BIND 写密码。
     */
    private suspend fun completeQgjBind(
        carInfo: CarInfo,
        bikeBind: BikeBind,
        device: BindingDevice,
        uid: String,
        modelType: Int,
        onStep: (String) -> Unit,
    ): BindOutcome {
        if (bikeBind.passwordMain.isBlank() || bikeBind.passwordChildren.isEmpty()) {
            throw BindException("服务器绑定", "服务器未下发 QGJ BLE 密码，无法继续")
        }
        val legacyV2 = QgjCodec.isLegacyV2(device.manufacturerData)

        var identityMac = ""
        var gpsImei = ""
        var gpsCode = ""
        val link = QgjLink(context, device.address)
        try {
            onStep("正在连接 QGJ 车辆…")
            link.connect()

            // Kuyi `TLinkBleManagerQgj.getMac()`：Harmony 设备连接后从 180a/2a23 读 System ID，
            // 其他设备用广播 manufacturer data，缺失时回退 BLE 地址。
            identityMac = if (device.harmony) {
                onStep("正在读取车辆 System ID…")
                try {
                    link.readSystemId()
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    throw BindException("读取车辆身份", t.describe())
                }
            } else {
                QgjCodec.identityMac(device.manufacturerData)
                    ?: Vehicle.formatMac(device.address)?.replace(":", "")
                    ?: throw BindException(
                        "读取车辆身份",
                        "无法解析车辆身份 MAC，请靠近车辆重新搜索后再配对",
                    )
            }

            if (modelType == 283) {
                onStep("正在读取 GPS IMEI 与 code…")
                val gps = try {
                    link.readGpsIdentity()
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    throw BindException("读取 GPS 身份", t.describe())
                }
                gpsImei = gps.first
                // 官方 v3Code 会去掉所有双引号；IMEI 保持原样。
                gpsCode = gps.second.replace("\"", "")
            }

            onStep("正在回写车辆 MAC…")
            val body = buildQgjBindBody(
                carInfo = carInfo,
                bikeBind = bikeBind,
                identityMac = identityMac,
                uid = uid,
                bleName = device.name,
                imei = gpsImei,
                code = gpsCode,
            )
            val token = authRepository.getValidToken()
            try {
                if (modelType == 283) {
                    bindApi.bindAgain(token, body)
                } else {
                    bindApi.bikeBindAgain(token, body)
                }
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                throw BindException("回写车辆 MAC", t.describe())
            }

            onStep("正在写入 QGJ 蓝牙密码…")
            try {
                link.bind(
                    mainPassword = bikeBind.passwordMain,
                    childPasswords = bikeBind.passwordChildren,
                    legacyV2 = legacyV2,
                )
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                throw BindException("写入车辆密码", t.describe())
            }
        } finally {
            link.close()
        }

        onStep("服务器确认绑定…")
        val token = authRepository.getValidToken()
        val updated = runCatching { bindApi.updateCarInfo(token, carInfo.carId, "1") }.getOrNull()
        val frame = updated?.optString("frame")?.takeIf { it.isNotBlank() } ?: carInfo.frame
        val activation = runCatching { bindApi.activeGet(token, frame) }.getOrNull()

        val local = bikeBind.toVehicle(
            uid = uid,
            btName = device.name,
            btMac = device.address,
            fallbackMac = identityMac,
            fallbackFrame = carInfo.frame,
            fallbackName = carInfo.carName,
        ).copy(
            modelType = modelType,
            imei = bikeBind.imei.ifBlank { gpsImei },
        )
        vehicleRepository.save(local, makeActive = true)

        onStep("刷新车辆信息…")
        val vehicle = runCatching { vehicleRepository.fetchFull(token, carInfo.carId) }
            .getOrElse { local }

        onStep("验证车辆登录…")
        val verifyWarning = verifyLogin(vehicle, uid)
        val activationWarning = if (activation != null && activation.isActivate == 0) {
            "车辆尚未激活，控车可能受限，请先在官方 App 完成激活"
        } else {
            null
        }
        return BindOutcome(
            vehicle = vehicle,
            activation = activation,
            warning = listOfNotNull(activationWarning, verifyWarning).joinToString("；").ifBlank { null },
        )
    }

    private fun buildQgjBindBody(
        carInfo: CarInfo,
        bikeBind: BikeBind,
        identityMac: String,
        uid: String,
        bleName: String,
        imei: String,
        code: String,
    ): JSONObject = JSONObject().apply {
        put("carId", carInfo.carId)
        put(
            "password",
            JSONObject().apply {
                put("main", bikeBind.passwordMain)
                put("children", JSONArray(bikeBind.passwordChildren))
            },
        )
        put("mac", identityMac)
        put("uid", uid)
        put("bltName", bleName)
        if (imei.isNotBlank() || code.isNotBlank()) {
            put("imei", imei)
            put("code", code)
        }
    }

    private fun buildBindAgainBody(
        carInfo: CarInfo,
        bikeBind: BikeBind,
        identity: com.ywpc.tlkey.ble.ResetIdentity,
        uid: String,
        bleName: String,
        modelType: Int,
    ): JSONObject = JSONObject().apply {
        put("carId", carInfo.carId)
        put(
            "password",
            JSONObject().apply {
                put("main", bikeBind.passwordMain)
                put("children", JSONArray(bikeBind.passwordChildren))
            },
        )
        put("mac", identity.systemIdHex)
        put("uid", uid)
        put("bltName", bleName)
        if (modelType == 10 || modelType == 14) {
            put("code", identity.code)
        }
        if (modelType in V3_MODEL_TYPES) {
            put("imei", identity.imei)
            put("code", identity.code)
        }
    }

    private suspend fun verifyLogin(vehicle: Vehicle, uid: String): String? {
        val protocol = ProtocolSelector.create(vehicle.modelType)
            ?: return "该车型暂不支持控制"
        val session: VehicleLink = if (protocol.family == ProtocolFamily.QGJ) {
            QgjSession(context, vehicle, uid)
        } else {
            BleSession(context, vehicle, protocol, uid)
        }
        return try {
            session.connectAndAuthenticate(NoopTrace)
            // 官方 283 绑定在 BLE_AUTH 登录成功后执行
            // connectGpsByUart() → gpsActivateSet(ON, 101)。
            if (vehicle.modelType == 283 && session is QgjSession) {
                try {
                    session.activateGps(NoopTrace)
                } catch (t: CancellationException) {
                    throw t
                } catch (t: Throwable) {
                    return "密码已写入，但 GPS 激活失败：${t.describe()}"
                }
            }
            null
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            "密码已写入，但重新登录验证失败：${t.describe()}"
        } finally {
            session.close()
        }
    }

    private fun Throwable.describe(): String =
        message?.takeIf { it.isNotBlank() } ?: this::class.java.simpleName

    private object NoopTrace : TraceSink {
        override fun mark(name: String) = Unit
    }

    private companion object {
        val SUPPORTED_BLE_TYPES = setOf(3, 10, 14, 401, 928, 1501, 1601, 1701, 2103, 2201)
        val V3_MODEL_TYPES = setOf(401, 928, 1501, 1601, 1701, 2103, 2201)
    }
}
