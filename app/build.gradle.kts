plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.dt.streamz"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.dt.streamz"
        // 26 covers Android 8+ — the SuperBox V3 ships Android 9, VSeebox V3
        // ships Android 11. Lowering from 30 unblocks SuperBox installs; no
        // code path requires API 30+ (no @RequiresApi / SDK_INT branches).
        minSdk = 26
        targetSdk = 35
        versionCode = 47
        versionName = "0.4.33"
    }

    signingConfigs {
        create("release") {
            // Stable keystore committed to the repo so every CI build signs
            // with the same key. Personal sideload app — the "anyone can
            // build a same-signed APK" risk is acceptable and beats the
            // prior behavior where every CI build used a fresh debug key
            // and every update failed with INSTALL_FAILED_UPDATE_INCOMPATIBLE.
            storeFile = rootProject.file("keystore/dt-streamz.jks")
            storePassword = "dtstreamz"
            keyAlias = "dt-streamz"
            keyPassword = "dtstreamz"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // NewPipeExtractor's YouTube parser calls API 33+ JDK methods
        // (URLEncoder.encode(String, Charset), Collectors.toUnmodifiableList).
        // The box is API 30, so without core-library desugaring those throw
        // NoSuchMethodError the moment we extract a stream. The `_nio` variant
        // is required — the default desugar artifact does NOT backport the
        // URLEncoder Charset overload.
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets {
        named("main") {
            kotlin.srcDirs("src/main/kotlin")
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.tv.material)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.ui)

    implementation(libs.newpipe.extractor)
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.1.5")

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.okhttp)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.core.ktx)

    implementation(libs.coil.compose)
    implementation(libs.coil.network)
}
