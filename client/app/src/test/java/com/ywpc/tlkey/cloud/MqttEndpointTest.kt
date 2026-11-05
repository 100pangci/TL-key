package com.ywpc.tlkey.cloud

import com.ywpc.tlkey.vehicle.Vehicle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MqttEndpointTest {

    @Test
    fun kks_usesBuiltInBrokerAndImeiTopics() {
        val result = MqttEndpoints.resolve(
            vehicle(modelType = 1, imei = "861234567890123"),
            uid = "user-1",
            randomSuffix = "042",
            legacyUser = "test-user",
            legacyPassword = "test-pass",
        )

        val endpoint = (result as MqttEndpointResult.Ready).endpoint
        assertEquals("tcp://www.tailgdd.com:1883", endpoint.host)
        assertEquals("test-user", endpoint.username)
        assertEquals("test-pass", endpoint.password)
        assertEquals("app_861234567890123042", endpoint.clientId)
        assertEquals(listOf("kks-get-861234567890123"), endpoint.subscribeTopics)
        assertEquals("app-update-kks/861234567890123", endpoint.publishTopic)
        assertFalse(endpoint.tls)
    }

    @Test
    fun yj_usesYunjiaTopicPrefix() {
        val result = MqttEndpoints.resolve(
            vehicle(modelType = 2, imei = "IMEI-1"),
            uid = "u",
            randomSuffix = "007",
            legacyUser = "test-user",
            legacyPassword = "test-pass",
        )

        val endpoint = (result as MqttEndpointResult.Ready).endpoint
        assertEquals(listOf("yunjia-get-IMEI-1"), endpoint.subscribeTopics)
        assertEquals("app-update-yunjia/IMEI-1", endpoint.publishTopic)
    }

    @Test
    fun missingLegacyCredentialsIsReported() {
        val result = MqttEndpoints.resolve(
            vehicle(modelType = 1, imei = "861234567890123"),
            uid = "user-1",
            legacyUser = "",
            legacyPassword = "",
        )

        assertEquals(
            MqttUnavailableReason.MISSING_CREDENTIALS,
            (result as MqttEndpointResult.Unavailable).reason,
        )
    }

    @Test
    fun qgj_usesCarStatusBrokerAndImeiGpsTopics() {
        val result = MqttEndpoints.resolve(
            vehicle(
                modelType = 283,
                imeiGps = "GPS-1",
                userId = "uid-9",
                mqUsername = "mq-user",
                mqPassword = "mq-pass",
            ),
            uid = "uid-9",
            randomSuffix = "123",
        )

        val endpoint = (result as MqttEndpointResult.Ready).endpoint
        assertEquals("ssl://www.tailgdd.com:6668", endpoint.host)
        assertEquals("mq-user", endpoint.username)
        assertEquals("mq-pass", endpoint.password)
        assertEquals("app_GPS-1_uid-9_android_123", endpoint.clientId)
        assertEquals(
            listOf("S_APP/STATUS/GPS-1", "S_APP/OTA/GPS-1"),
            endpoint.subscribeTopics,
        )
        assertEquals("APP_S/CMD/GPS-1", endpoint.publishTopic)
        assertTrue(endpoint.tls)
    }

    @Test
    fun connectionTargetComparison_ignoresRandomClientId() {
        val first = (
            MqttEndpoints.resolve(
                vehicle(
                    modelType = 283,
                    imeiGps = "GPS-1",
                    userId = "uid-9",
                    mqUsername = "mq-user",
                    mqPassword = "mq-pass",
                ),
                uid = "uid-9",
                randomSuffix = "001",
            ) as MqttEndpointResult.Ready
            ).endpoint
        val second = (
            MqttEndpoints.resolve(
                vehicle(
                    modelType = 283,
                    imeiGps = "GPS-1",
                    userId = "uid-9",
                    mqUsername = "mq-user",
                    mqPassword = "mq-pass",
                ),
                uid = "uid-9",
                randomSuffix = "002",
            ) as MqttEndpointResult.Ready
            ).endpoint

        assertFalse(first.clientId == second.clientId)
        assertTrue(first.sameConnectionTarget(second))
        assertFalse(first.sameConnectionTarget(second.copy(host = "ssl://other:6668")))
    }

    @Test
    fun c18_addsChargerAndCheckTopics() {
        val endpoint = (
            MqttEndpoints.resolve(
                vehicle(
                    modelType = 10,
                    imeiGps = "GPS-2",
                    userId = "u",
                    mqUsername = "user",
                    mqPassword = "pass",
                ),
                uid = "u",
                randomSuffix = "001",
            ) as MqttEndpointResult.Ready
            ).endpoint

        assertEquals(
            listOf(
                "S_APP/STATUS/GPS-2",
                "S_APP/OTA/GPS-2",
                "S_APP/CHARGER/GPS-2",
                "S_APP/CHECK/GPS-2",
            ),
            endpoint.subscribeTopics,
        )
    }

    @Test
    fun carStatusHostAndPortOverrideDefaultBroker() {
        val endpoint = (
            MqttEndpoints.resolve(
                vehicle(
                    modelType = 8,
                    imeiGps = "GPS-3",
                    userId = "u",
                    mqHost = "broker.example.com",
                    mqPort = "8883",
                    mqUsername = "user",
                    mqPassword = "pass",
                ),
                uid = "u",
                randomSuffix = "555",
            ) as MqttEndpointResult.Ready
            ).endpoint

        assertEquals("ssl://broker.example.com:8883", endpoint.host)
    }

    @Test
    fun missingQgjCredentialsIsReported() {
        val result = MqttEndpoints.resolve(
            vehicle(modelType = 8, imeiGps = "GPS-4", userId = "u"),
            uid = "u",
        )

        assertEquals(
            MqttUnavailableReason.MISSING_CREDENTIALS,
            (result as MqttEndpointResult.Unavailable).reason,
        )
    }

    @Test
    fun missingImeiGpsIsReported() {
        val result = MqttEndpoints.resolve(
            vehicle(modelType = 8, userId = "u", mqUsername = "u", mqPassword = "p"),
            uid = "u",
        )

        assertEquals(
            MqttUnavailableReason.MISSING_IMEI_GPS,
            (result as MqttEndpointResult.Unavailable).reason,
        )
    }

    @Test
    fun parseStatus_readsOfficialPayloadFields() {
        val status = MqttPayload.parse(
            """{"ACC":"1","defenceStatus":"1","accErrorStatus":0,"muteStatus":1,"seq":7}""",
        )

        assertEquals("1", status?.acc)
        assertEquals(true, status?.accOn)
        assertEquals(true, status?.locked)
        assertEquals(true, status?.muted)
        assertEquals(7, status?.seq)
    }

    @Test
    fun mergedStatus_keepsPreviousFieldsWhenPayloadOmitsThem() {
        val first = MqttStatus(acc = "1", defenceStatus = "0")
        val merged = first.mergedWith(MqttStatus(defenceStatus = "1"))

        assertEquals("1", merged.acc)
        assertEquals("1", merged.defenceStatus)
    }

    @Test
    fun commandPayloadMatchesOfficialGsonShape() {
        val json = MqttPayload.command("IMEI-9", CloudCommand.UNLOCK.wire)

        assertEquals("""{"imei":"IMEI-9","command":"unlock"}""", json)
    }

    @Test
    fun ackSemantics_matchOfficialAccAndDefenceStatus() {
        assertEquals(true, CloudCommand.POWER_ON.successOf(MqttStatus(acc = "1")))
        assertEquals(true, CloudCommand.POWER_OFF.successOf(MqttStatus(acc = "0")))
        assertEquals(true, CloudCommand.LOCK.successOf(MqttStatus(defenceStatus = "1")))
        assertEquals(true, CloudCommand.UNLOCK.successOf(MqttStatus(defenceStatus = "0")))
        assertNull(CloudCommand.FIND.successOf(MqttStatus(acc = "1")))
        assertEquals(8, CloudCommand.LOCK.errorCodeOf(MqttStatus(defenceErrorStatus = 8)))
        assertNull(CloudCommand.LOCK.errorCodeOf(MqttStatus(defenceErrorStatus = 0)))
    }

    private fun vehicle(
        modelType: Int,
        imei: String = "",
        imeiGps: String = "",
        userId: String = "U",
        mqHost: String = "",
        mqPort: String = "",
        mqUsername: String = "",
        mqPassword: String = "",
    ): Vehicle = Vehicle(
        id = "car-1",
        name = "车",
        carType = "T",
        modelType = modelType,
        mac = "",
        btName = null,
        btMac = null,
        btPin = null,
        passwordMain = "",
        passwordChildren = emptyList(),
        shareCarFlag = false,
        frame = "F",
        imei = imei,
        userId = userId,
        mqHost = mqHost,
        mqPort = mqPort,
        mqUsername = mqUsername,
        mqPassword = mqPassword,
        imeiGps = imeiGps,
    )
}
