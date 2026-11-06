import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// The license secret stays out of git. It must not change, or existing keys stop working.
val localProperties = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.reader()?.use(::load)
}
val licenseKey: String = localProperties.getProperty("license.hmacKey")
    ?: error("Add license.hmacKey to local.properties")

android {
    namespace = "com.autoclicker.chattask"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.autoclicker.chattask"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
        val signer = providers.gradleProperty("app.signerSha256").get()
        buildConfigField("String", "SIGNER_SHA256", "\"$signer\"")
        buildConfigField("String", "LICENSE_HMAC_KEY", "\"$licenseKey\"")
    }

    buildTypes {
        val hardened: com.android.build.api.dsl.ApplicationBuildType.() -> Unit = {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        release { hardened() }
        // app-debug.apk is the build that gets distributed, so it is hardened like a release.
        debug {
            hardened()
            isDebuggable = false
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
        viewBinding = true
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.security:security-crypto:1.0.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
