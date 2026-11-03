package com.ywpc.tlkey.ble

import java.util.UUID

object BleUuids {
    val SERVICE_FEE5: UUID = UUID.fromString("0000fee5-0000-1000-8000-00805f9b34fb")
    val CHAR_FEB5: UUID = UUID.fromString("0000feb5-0000-1000-8000-00805f9b34fb")
    val CHAR_FEB6: UUID = UUID.fromString("0000feb6-0000-1000-8000-00805f9b34fb")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    val SERVICE_180A: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
    val CHAR_2A23: UUID = UUID.fromString("00002a23-0000-1000-8000-00805f9b34fb")
    val CHAR_2A40: UUID = UUID.fromString("00002a40-0000-1000-8000-00805f9b34fb")
    val CHAR_2A41: UUID = UUID.fromString("00002a41-0000-1000-8000-00805f9b34fb")

    // ECU（85 帧与 QGJ 共用）：fcc1 或 fbb1 二选一，原始字节读写，无 AES。
    val SERVICE_FCC0: UUID = UUID.fromString("0000fcc0-0000-1000-8000-00805f9b34fb")
    val CHAR_FCC1: UUID = UUID.fromString("0000fcc1-0000-1000-8000-00805f9b34fb")
    val CHAR_FCC2: UUID = UUID.fromString("0000fcc2-0000-1000-8000-00805f9b34fb")
    val CHAR_FBB1: UUID = UUID.fromString("0000fbb1-0000-1000-8000-00805f9b34fb")
    val CHAR_FBB2: UUID = UUID.fromString("0000fbb2-0000-1000-8000-00805f9b34fb")

    // QGJ（Kuyi SDK）两代 ECU：
    // V1：service ffe1 + write fee2 + indicate fee3（g.r0 / q0）。
    // V2：service feb0 + write feb1 + indicate feb2（h.f1 / e1）。
    val SERVICE_QGJ_V2: UUID = UUID.fromString("0000feb0-0000-1000-8000-00805f9b34fb")
    val CHAR_QGJ_V2_TX: UUID = UUID.fromString("0000feb1-0000-1000-8000-00805f9b34fb")
    val CHAR_QGJ_V2_RX: UUID = UUID.fromString("0000feb2-0000-1000-8000-00805f9b34fb")

    val SERVICE_QGJ_V1: UUID = UUID.fromString("0000ffe1-0000-1000-8000-00805f9b34fb")
    val CHAR_QGJ_V1_TX: UUID = UUID.fromString("0000fee2-0000-1000-8000-00805f9b34fb")
    val CHAR_QGJ_V1_RX: UUID = UUID.fromString("0000fee3-0000-1000-8000-00805f9b34fb")

    /** QGJ GPS/仪表通道：fe01 + fe02(write) + fe03(notify/indicate)。 */
    val SERVICE_QGJ_GPS: UUID = UUID.fromString("0000fe01-0000-1000-8000-00805f9b34fb")
    val CHAR_QGJ_GPS_TX: UUID = UUID.fromString("0000fe02-0000-1000-8000-00805f9b34fb")
    val CHAR_QGJ_GPS_RX: UUID = UUID.fromString("0000fe03-0000-1000-8000-00805f9b34fb")
}
