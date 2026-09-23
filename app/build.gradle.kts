plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

detekt {
    config.setFrom(rootProject.layout.projectDirectory.file("config/detekt.yml"))
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

// detekt otherwise defaults --jvm-target to the Gradle JVM, so the check fails
// on any JDK newer than the bundled analyzer supports. Pin it to the target
// declared above so the result is the same on every machine.
tasks.withType(dev.detekt.gradle.Detekt::class.java).configureEach {
    jvmTarget.set("17")
}

// Release signing keystore provided by CI env; null locally.
val ciKeystoreFile: String? = providers.environmentVariable("KEYSTORE_FILE").orNull

val appVersion = "0.3.0"

android {
    namespace = "com.telestash"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.telestash"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = appVersion
        // TDLib reports this on log-in; keep it in sync with versionName.
        buildConfigField("String", "APP_VERSION", "\"$appVersion\"")
    }

    signingConfigs {
        create("release") {
            ciKeystoreFile?.let { storeFile = file(it) }
            storePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
            keyAlias = providers.environmentVariable("KEY_ALIAS").orNull
            keyPassword = providers.environmentVariable("KEY_PASSWORD").orNull
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
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
        buildConfig = true
    }
}

dependencies {
    implementation(files("libs/core-release.aar"))
    implementation(libs.androidx.core)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.foundation)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.room.runtime)
    implementation(libs.room.paging)
    implementation(libs.paging.runtime)
    implementation(libs.paging.compose)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.datastore.prefs)
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.zoomable)
}