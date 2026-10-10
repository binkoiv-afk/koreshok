plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    // Screenshot tests: CI renders the main screens on the JVM so the design can be checked without a phone.
    alias(libs.plugins.paparazzi)
}

// CI passes the run number, so every published build has a higher version than the last.
val buildNumber = System.getenv("VERSION_CODE")?.toIntOrNull() ?: 1
// The release key lives in the repo, encrypted; its password is a GitHub secret. Without either
// (local builds, or before the owner sets the secret) the build falls back to the debug key.
val keystorePassword: String? = System.getenv("KEYSTORE_PASSWORD")?.takeIf { it.isNotEmpty() }
    ?.takeIf { file("signing/koreshok.jks").exists() }

android {
    namespace = "app.koreshok"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.koreshok"
        minSdk = 26
        targetSdk = 35
        versionCode = buildNumber
        versionName = "0.1.$buildNumber"
    }

    signingConfigs {
        if (keystorePassword != null) {
            create("release") {
                storeFile = file("signing/koreshok.jks")
                storePassword = keystorePassword
                keyAlias = "koreshok"
                keyPassword = keystorePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.documentfile)
    implementation(libs.coil.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    implementation(libs.junrar)
    implementation(libs.play.services.auth)

    testImplementation(libs.junit)
}
