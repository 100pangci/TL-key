package com.ywpc.tlkey

import android.app.Application
import com.ywpc.tlkey.auth.AuthApi
import com.ywpc.tlkey.auth.AuthRepository
import com.ywpc.tlkey.auth.LoginManager
import com.ywpc.tlkey.auth.TokenStore
import com.ywpc.tlkey.ble.BleScanner
import com.ywpc.tlkey.control.VehicleController
import com.ywpc.tlkey.net.HttpClient
import com.ywpc.tlkey.storage.AppPreferences
import com.ywpc.tlkey.storage.SecureStorage
import com.ywpc.tlkey.vehicle.BindApi
import com.ywpc.tlkey.vehicle.BindRepository
import com.ywpc.tlkey.vehicle.EcuApi
import com.ywpc.tlkey.vehicle.OtaApi
import com.ywpc.tlkey.vehicle.VehicleApi
import com.ywpc.tlkey.vehicle.VehicleRepository
import com.ywpc.tlkey.vehicle.VehicleStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class App : Application() {

    lateinit var secureStorage: SecureStorage
        private set

    lateinit var tokenStore: TokenStore
        private set

    lateinit var authRepository: AuthRepository
        private set

    lateinit var vehicleRepository: VehicleRepository
        private set

    lateinit var loginManager: LoginManager
        private set

    lateinit var bindRepository: BindRepository
        private set

    lateinit var preferences: AppPreferences
        private set

    lateinit var controller: VehicleController
        private set

    lateinit var otaApi: OtaApi
        private set

    lateinit var ecuApi: EcuApi
        private set

    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate() {
        super.onCreate()
        instance = this

        secureStorage = SecureStorage(this)
        tokenStore = TokenStore(secureStorage)
        preferences = AppPreferences(this)

        val http = HttpClient()
        val authApi = AuthApi(http)
        authRepository = AuthRepository(authApi, tokenStore)

        val vehicleApi = VehicleApi(http)
        otaApi = OtaApi(http)
        ecuApi = EcuApi(http)
        val vehicleStore = VehicleStore(secureStorage)
        vehicleRepository = VehicleRepository(vehicleApi, vehicleStore)
        bindRepository = BindRepository(
            context = this,
            authRepository = authRepository,
            bindApi = BindApi(http),
            vehicleRepository = vehicleRepository,
            scanner = BleScanner(this),
        )

        loginManager = LoginManager(authRepository, vehicleRepository)
        controller = VehicleController(
            context = this,
            authRepository = authRepository,
            vehicleRepository = vehicleRepository,
            preferences = preferences,
            appScope = appScope,
        )
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
