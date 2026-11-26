import java.io.File

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val turnipLibraries = providers.gradleProperty("benchTurnipInstall")

android {
    namespace = "network.midnight.vulkanbench"
    compileSdk = 35

    defaultConfig {
        applicationId = "network.midnight.vulkanbench"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
        manifestPlaceholders["benchAppLabel"] = "@string/app_name"
        buildConfigField("boolean", "USE_TURNIP", "false")

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("KEYSTORE_PATH")
                ?: rootProject.file("signing/release-bench.keystore").takeIf { it.exists() }?.absolutePath
            if (keystorePath != null && File(keystorePath).exists()) {
                storeFile = File(keystorePath)
                storePassword = System.getenv("KEYSTORE_PASSWORD") ?: "midnight-bench"
                keyAlias = System.getenv("KEY_ALIAS") ?: "midnight-bench"
                keyPassword = System.getenv("KEY_PASSWORD") ?: "midnight-bench"
            }
        }
    }

    buildTypes {
        debug {
            if (turnipLibraries.isPresent) {
                applicationIdSuffix = ".turnip"
                versionNameSuffix = "-turnip"
                manifestPlaceholders["benchAppLabel"] = "Midnight Bench (Turnip)"
                buildConfigField("boolean", "USE_TURNIP", "true")
            } else if (providers.gradleProperty("benchCompatInstall").isPresent) {
                applicationIdSuffix = ".compat"
                versionNameSuffix = "-compat"
                manifestPlaceholders["benchAppLabel"] = "Midnight Bench (Compat)"
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val relSigning = signingConfigs.getByName("release")
            signingConfig = if (relSigning.storeFile != null) {
                relSigning
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    turnipLibraries.orNull?.let { directory ->
        sourceSets.named("debug") {
            jniLibs.srcDir(directory)
            assets.srcDir(file(directory).resolve("notices"))
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = turnipLibraries.isPresent
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
