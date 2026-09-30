val gitVersionCode = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.map { it.trim().toIntOrNull() ?: 1 }

val gitVersionName = providers.exec {
    commandLine("git", "describe", "--tags", "--always")
}.standardOutput.asText.map { it.trim() }

plugins {
    id("com.android.application")
}

android {
    namespace = "net.wastu.solipsistic"
    compileSdk = 34

    defaultConfig {
        applicationId = "net.wastu.solipsistic"
        minSdk = 26
        targetSdk = 34
        versionCode = gitVersionCode.get()
        versionName = gitVersionName.get()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    signingConfigs {
        val keystorePath = System.getenv("ANDROID_KEYSTORE_FILE")
        val keystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
        val keystoreAlias = System.getenv("ANDROID_KEYSTORE_ALIAS")

        if (!keystorePath.isNullOrBlank() && !keystorePassword.isNullOrBlank() && !keystoreAlias.isNullOrBlank()) {
            create("release") {
                storeFile = file(keystorePath)
                storePassword = keystorePassword
                keyAlias = keystoreAlias
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it } ?: run {
                signingConfig = signingConfigs.getByName("debug")
            }
            isMinifyEnabled = false
        }
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}
