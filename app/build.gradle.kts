plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

detekt {
    config.setFrom(files("$rootDir/config/detekt.yml"))
    buildUponDefaultConfig = true
}

ktlint {
    // Kotlin 2.2 scripts trip the old ktlint parser bundled with the plugin;
    // source files are still fully checked.
    filter {
        exclude { it.file.extension == "kts" }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Release signing keystore provided by CI env; null locally.
val ciKeystoreFile: String? = System.getenv("KEYSTORE_FILE")

android {
    namespace = "com.telestash"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.telestash"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.3.0"
    }

    signingConfigs {
        create("release") {
            // Populated by CI (secrets -> env) for real releases.
            ciKeystoreFile?.let { storeFile = file(it) }
            storePassword = System.getenv("KEYSTORE_PASSWORD")
            keyAlias = System.getenv("KEY_ALIAS")
            keyPassword = System.getenv("KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Without CI signing secrets the APK is debug-signed so it stays installable.
            signingConfig =
                if (ciKeystoreFile != null) {
                    signingConfigs.getByName("release")
                } else {
                    signingConfigs.getByName("debug")
                }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }
}

dependencies {
    implementation(files("libs/core-release.aar"))
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.datastore.prefs)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.zoomable)
}
