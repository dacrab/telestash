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

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
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

val appVersion = "0.3.0"
val releaseTag = providers.gradleProperty("releaseTag").orNull
val signRelease = providers.gradleProperty("signRelease").orNull == "true"

if (releaseTag != null) {
    require(signRelease) { "Tagged release builds require signRelease=true" }
    require(Regex("""v\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?""").matches(releaseTag)) {
        "Release tag must be a semantic version"
    }
}
if (signRelease) {
    require(releaseTag != null) { "signRelease=true requires releaseTag" }
}
val releaseVersion = releaseTag?.removePrefix("v") ?: appVersion
val releaseVersionCode =
    releaseTag?.let {
        val parts = releaseVersion.substringBefore('-').split('.').map(String::toInt)
        require(parts.size == 3 && parts.all { part -> part in 0..999 })
        parts.fold(0) { code, part -> code * 1000 + part }
    } ?: 3

val keystoreFile = providers.environmentVariable("KEYSTORE_FILE").orNull
val keystorePassword = providers.environmentVariable("KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("KEY_PASSWORD").orNull

if (signRelease) {
    require(!keystoreFile.isNullOrBlank()) { "KEYSTORE_FILE is required when signing a release" }
    require(!keystorePassword.isNullOrBlank()) { "KEYSTORE_PASSWORD is required when signing a release" }
    require(!releaseKeyAlias.isNullOrBlank()) { "KEY_ALIAS is required when signing a release" }
    require(!releaseKeyPassword.isNullOrBlank()) { "KEY_PASSWORD is required when signing a release" }
}

android {
    namespace = "com.telestash"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.telestash"
        minSdk = 26
        targetSdk = 37
        versionCode = releaseVersionCode
        versionName = releaseVersion
        // TDLib reports this on log-in; keep it in sync with versionName.
        buildConfigField("String", "APP_VERSION", "\"$releaseVersion\"")
    }

    signingConfigs {
        if (signRelease) {
            create("release") {
                storeFile = file(checkNotNull(keystoreFile))
                storePassword = checkNotNull(keystorePassword)
                keyAlias = checkNotNull(releaseKeyAlias)
                keyPassword = checkNotNull(releaseKeyPassword)
            }
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
            if (signRelease) {
                signingConfig = signingConfigs.getByName("release")
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
    testImplementation(libs.junit)
}
