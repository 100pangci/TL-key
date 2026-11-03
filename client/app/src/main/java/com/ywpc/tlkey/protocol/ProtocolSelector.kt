package com.ywpc.tlkey.protocol

object ProtocolSelector {

    private val NEW_85_TYPES = setOf(3, 10, 14, 401, 928, 1501, 1601, 1701, 2103, 2201)
    private val LEGACY_78_TYPES = setOf(1, 2)
    private val QGJ_TYPES = setOf(8, 283)

    fun familyFor(modelType: Int): ProtocolFamily? = when (modelType) {
        in LEGACY_78_TYPES -> ProtocolFamily.LEGACY_78
        in NEW_85_TYPES -> ProtocolFamily.NEW_85
        in QGJ_TYPES -> ProtocolFamily.QGJ
        else -> null
    }

    fun create(modelType: Int): VehicleProtocol? = when (modelType) {
        in LEGACY_78_TYPES -> ProtocolKeys.legacy78()?.let(::Protocol78)
        in NEW_85_TYPES -> ProtocolKeys.forNewProtocol(modelType)?.let(::Protocol85)
        in QGJ_TYPES -> QgjProtocol()
        else -> null
    }
}
