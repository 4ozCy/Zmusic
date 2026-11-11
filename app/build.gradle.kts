plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// CI decodes ci/zmusic.keystore.b64 to ci/zmusic.jks. The key is committed on purpose so every
// build has the SAME signature and new APKs install over old ones. It is public, so this is only
// fine for a personal sideloaded app. Do not publish to Play with it.
val releaseKeystore = rootProject.file("ci/zmusic.jks")

android {
    namespace = "com.zmusic.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.zmusic.app"
        minSdk = 26
        targetSdk = 35
        // CI passes the run number so each release has a higher versionCode than the last.
        versionCode = (project.findProperty("verCode") as String?)?.toIntOrNull() ?: 1
        versionName = "0.2.${versionCode}"
    }

    signingConfigs {
        if (releaseKeystore.exists()) {
            create("zmusic") {
                storeFile = releaseKeystore
                storePassword = "zmusic123"
                keyAlias = "zmusic"
                keyPassword = "zmusic123"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (releaseKeystore.exists()) {
                signingConfigs.getByName("zmusic")
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
        freeCompilerArgs += "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
    }
    buildFeatures {
        compose = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
