plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Version, overridable from the command line so a release can be cut without editing this file:
 *
 *     ./gradlew :app:assembleRelease -PversionName=1.4.0 -PversionCode=10400
 *
 * Both fall back to the values below, so an ordinary build is unaffected.
 */
val appVersionName: String = providers.gradleProperty("versionName").getOrElse("1.0")
val appVersionCode: Int = providers.gradleProperty("versionCode").map { it.toInt() }.getOrElse(1)

/**
 * Release signing, read from the environment so a keystore never has to be committed.
 * `RELEASE_KEYSTORE_FILE` is a path to the keystore; the rest are its credentials.
 *
 * All four are optional together. When they are absent the release build still runs and simply
 * emits `app-release-unsigned.apk` rather than failing, which keeps `assembleRelease` usable
 * locally without a keystore. The release workflow checks the signature separately, so a missing
 * keystore is reported as "configure your secrets" instead of a Gradle stack trace.
 */
val releaseKeystoreFile: String? = providers.environmentVariable("RELEASE_KEYSTORE_FILE").orNull
val releaseKeystorePassword: String? = providers.environmentVariable("RELEASE_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias: String? = providers.environmentVariable("RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword: String? = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
val hasReleaseSigning: Boolean = listOf(
    releaseKeystoreFile,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "com.itzdfplayer.opengimbal"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.itzdfplayer.opengimbal"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseKeystoreFile.orEmpty())
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Attached only when a keystore was supplied, so a local build still works.
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}