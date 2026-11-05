package com.ywpc.tlkey.control

import com.ywpc.tlkey.protocol.ControlAction
import org.junit.Assert.assertEquals
import org.junit.Test

class ControlResultTest {

    @Test
    fun successMessage_marksCloudFallback() {
        val trace = ControlTrace(ControlAction.UNLOCK)

        assertEquals(
            "开锁成功",
            ControlResult.Success(ControlAction.UNLOCK, trace).userMessage(),
        )
        assertEquals(
            "开锁成功（云控）",
            ControlResult.Success(ControlAction.UNLOCK, trace, viaCloud = true).userMessage(),
        )
    }

    @Test
    fun publishOnlyCloudSuccess_isReportedAsSent() {
        val trace = ControlTrace(ControlAction.FIND)

        assertEquals(
            "寻车指令已发送（云控，无回执）",
            ControlResult.Success(
                ControlAction.FIND,
                trace,
                viaCloud = true,
                sentOnly = true,
            ).userMessage(),
        )
    }
}
