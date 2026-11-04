import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 正式版签名配置保存在 client/keystore.properties（本机文件，已被 .gitignore 忽略）。
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
val hasReleaseKeystore = keystoreProperties.getProperty("storeFile") != null

// 仓库不内置任何共享凭据或协议密钥。以下值在构建时通过 Gradle 属性
// （-Ptlkey.xxx=...）或环境变量（TLKEY_...）注入，也可由 CI secrets 提供。
fun buildSecret(property: String, environment: String): String =
    (project.findProperty(property) as? String)?.trim()?.takeIf { it.isNotEmpty() }
        ?: System.getenv(environment)?.trim().orEmpty()

fun kotlinString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

val mqttLegacyUser = buildSecret("tlkey.mqtt.legacyUser", "TLKEY_MQTT_LEGACY_USER")
val mqttLegacyPassword = buildSecret("tlkey.mqtt.legacyPassword", "TLKEY_MQTT_LEGACY_PASSWORD")

val protocolKeys = listOf(
    "PROTOCOL_78_KEY" to buildSecret("tlkey.protocol78.key", "TLKEY_PROTOCOL78_KEY"),
    "PROTOCOL_85_BB" to buildSecret("tlkey.protocol85.bb", "TLKEY_PROTOCOL85_BB"),
    "PROTOCOL_85_JW" to buildSecret("tlkey.protocol85.jw", "TLKEY_PROTOCOL85_JW"),
    "PROTOCOL_85_JD" to buildSecret("tlkey.protocol85.jd", "TLKEY_PROTOCOL85_JD"),
    "PROTOCOL_85_AX" to buildSecret("tlkey.protocol85.ax", "TLKEY_PROTOCOL85_AX"),
    "PROTOCOL_85_HJ" to buildSecret("tlkey.protocol85.hj", "TLKEY_PROTOCOL85_HJ"),
    "PROTOCOL_85_XL" to buildSecret("tlkey.protocol85.xl", "TLKEY_PROTOCOL85_XL"),
    "PROTOCOL_85_YY" to buildSecret("tlkey.protocol85.yy", "TLKEY_PROTOCOL85_YY"),
)

// 版本号由 git 推导：
// - versionCode = HEAD 可达提交数 + 1（无 git 仓库时为 1，每次提交自动 +1）
// - versionName = 最近一个 tag（去掉前缀 v）；没有 tag 时回落到 0.1.0
// 发版时使用 vX.Y.Z tag，versionName 会自动取 X.Y.Z。
fun gitOutput(vararg args: String): String? = runCatching {
    val process = ProcessBuilder(listOf("git") + args)
        .directory(rootProject.projectDir)
        .redirectErrorStream(true)
        .start()
    val text = process.inputStream.bufferedReader().use { it.readText() }.trim()
    if (process.waitFor() == 0) text.takeIf { it.isNotEmpty() } else null
}.getOrNull()

val commitCount = gitOutput("rev-list", "--count", "HEAD")?.toIntOrNull() ?: 0
val appVersionCode = commitCount + 1
val appVersionName = gitOutput("describe", "--tags", "--abbrev=0")
    ?.removePrefix("v")
    ?.takeIf { it.isNotBlank() }
    ?: "0.1.0"

android {
    namespace = "com.ywpc.tlkey"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.ywpc.tlkey"
        minSdk = 26
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        buildConfigField("String", "MQTT_LEGACY_USER", kotlinString(mqttLegacyUser))
        buildConfigField("String", "MQTT_LEGACY_PASSWORD", kotlinString(mqttLegacyPassword))
        protocolKeys.forEach { (name, value) ->
            buildConfigField("String", name, kotlinString(value))
        }

        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            // Debug 与正式版可共存；shortcuts.xml 的 targetPackage 通过资源按变体覆盖。
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.datastore:datastore-preferences:1.1.7")

    testImplementation("junit:junit:4.13.2")
}
