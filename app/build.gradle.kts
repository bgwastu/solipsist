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
    namespace = "net.wastu.solipsist"
    compileSdk = 36

    defaultConfig {
        applicationId = "net.wastu.solipsist"
        minSdk = 26
        targetSdk = 34
        // Keep updates above the last installed release when branches have different commit counts.
        versionCode = maxOf(gitVersionCode.get(), 18)
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
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = false
        }
    }
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
}
