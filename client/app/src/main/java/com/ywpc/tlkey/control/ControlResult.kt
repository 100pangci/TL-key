package com.ywpc.tlkey.control

import com.ywpc.tlkey.protocol.ControlAction

sealed interface ControlResult {

    val trace: ControlTrace

    data class Success(
        val action: ControlAction,
        override val trace: ControlTrace,
        val viaCloud: Boolean = false,
        /** 云控 QoS 0 指令只确认已交给 socket，没有车辆回执。 */
        val sentOnly: Boolean = false,
    ) : ControlResult

    data class BluetoothDisabled(override val trace: ControlTrace) : ControlResult
    data class PermissionDenied(override val trace: ControlTrace) : ControlResult
    data class DeviceNotFound(override val trace: ControlTrace) : ControlResult
    data class ConnectTimeout(override val trace: ControlTrace) : ControlResult
    data class GattError(override val trace: ControlTrace, val status: Int) : ControlResult
    data class ServiceNotFound(override val trace: ControlTrace) : ControlResult
    data class AuthFailed(
        override val trace: ControlTrace,
        val message: String,
    ) : ControlResult

    data class CommandRejected(
        override val trace: ControlTrace,
        val message: String,
    ) : ControlResult

    data class CommandTimeout(override val trace: ControlTrace) : ControlResult
    data class TokenExpired(override val trace: ControlTrace) : ControlResult
    data class NetworkError(
        override val trace: ControlTrace,
        val message: String,
    ) : ControlResult

    data class Unknown(
        override val trace: ControlTrace,
        val message: String,
    ) : ControlResult
}

val ControlResult.isSuccess: Boolean
    get() = this is ControlResult.Success

fun ControlResult.userMessage(): String = when (this) {
    is ControlResult.Success ->
        if (sentOnly) {
            "${action.displayName()}指令已发送（云控，无回执）"
        } else {
            "${action.displayName()}成功${if (viaCloud) "（云控）" else ""}"
        }
    is ControlResult.BluetoothDisabled -> "蓝牙未开启"
    is ControlResult.PermissionDenied -> "缺少蓝牙权限"
    is ControlResult.DeviceNotFound -> "未找到车辆"
    is ControlResult.ConnectTimeout -> "连接车辆超时"
    is ControlResult.GattError -> "蓝牙通信错误（$status）"
    is ControlResult.ServiceNotFound -> "车辆蓝牙服务不匹配"
    is ControlResult.AuthFailed -> message
    is ControlResult.CommandRejected -> "车辆拒绝执行：$message"
    is ControlResult.CommandTimeout -> "车辆响应超时"
    is ControlResult.TokenExpired -> "登录已失效，请重新登录"
    is ControlResult.NetworkError -> "网络错误：$message"
    is ControlResult.Unknown -> message
}

fun ControlAction.displayName(): String = when (this) {
    ControlAction.UNLOCK -> "开锁"
    ControlAction.LOCK -> "上锁"
    ControlAction.POWER_ON -> "开机"
    ControlAction.POWER_OFF -> "关机"
    ControlAction.FIND -> "寻车"
    ControlAction.SEAT -> "座桶"
}
