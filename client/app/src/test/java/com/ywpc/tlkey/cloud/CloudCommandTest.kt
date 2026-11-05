package com.ywpc.tlkey.cloud

import com.ywpc.tlkey.protocol.ControlAction
import org.junit.Assert.assertEquals
import org.junit.Test

class CloudCommandTest {

    @Test
    fun everyControlActionMapsToCloudCommand() {
        val expected = linkedMapOf(
            ControlAction.UNLOCK to CloudCommand.UNLOCK,
            ControlAction.LOCK to CloudCommand.LOCK,
            ControlAction.POWER_ON to CloudCommand.POWER_ON,
            ControlAction.POWER_OFF to CloudCommand.POWER_OFF,
            ControlAction.FIND to CloudCommand.FIND,
            ControlAction.SEAT to CloudCommand.SEAT,
        )
        assertEquals(ControlAction.entries.toSet(), expected.keys)
        expected.forEach { (action, command) ->
            assertEquals(command, action.toCloudCommand())
        }
    }
}
