package com.ywpc.tlkey.control

import com.ywpc.tlkey.protocol.EcuState

sealed interface EcuResult {
    data class Success(
        val state: EcuState?,
        val message: String,
    ) : EcuResult

    data class Failure(val message: String) : EcuResult
}
