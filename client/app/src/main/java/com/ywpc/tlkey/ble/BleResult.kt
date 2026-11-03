package com.ywpc.tlkey.ble

sealed class BleException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    class BluetoothDisabled : BleException("蓝牙未开启")
    class PermissionDenied : BleException("缺少蓝牙权限")
    class DeviceNotFound : BleException("未找到车辆蓝牙设备")
    class ConnectTimeout : BleException("连接车辆超时")
    class GattError(val status: Int) : BleException("蓝牙 GATT 错误（$status）")
    class ServiceNotFound(message: String = "车辆蓝牙服务不匹配") : BleException(message)
    class NotificationTimeout : BleException("开启车辆通知超时")
    class AuthFailed(message: String = "车辆鉴权失败") : BleException(message)
    class AuthTimeout : BleException("车辆鉴权超时")
    class CommandRejected(message: String) : BleException(message)
    class CommandTimeout : BleException("车辆响应超时")
    class ReadTimeout(message: String = "读取车辆信息超时") : BleException(message)
    class Disconnected : BleException("蓝牙连接已断开")
    class Unknown(cause: Throwable) : BleException("蓝牙操作失败", cause)
}

/** 让 BLE 层只依赖一个轻量计时接口，避免反向依赖 control 包。 */
fun interface TraceSink {
    fun mark(name: String)
}
