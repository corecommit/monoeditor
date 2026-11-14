plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.mono.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "dev.mono.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    // Toolchain executables ship as native libraries and must be extracted to
    // disk so they can be executed from nativeLibraryDir (API 29+ W^X).
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            shrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // CI signing: env vars are set by the workflow (secret keystore or
            // a generated CI keystore). Local builds fall back to debug keys.
            val ksPath = System.getenv("MONO_KEYSTORE_FILE")
            val ksPass = System.getenv("MONO_KEYSTORE_PASSWORD")
            val ksAlias = System.getenv("MONO_KEY_ALIAS")
            val ksKeyPass = System.getenv("MONO_KEY_PASSWORD")
            if (ksPath != null && ksPass != null && ksAlias != null && ksKeyPass != null) {
                signingConfig = signingConfigs.create("ci") {
                    storeFile = file(ksPath)
                    storePassword = ksPass
                    keyAlias = ksAlias
                    keyPassword = ksKeyPass
                }
            } else {
                signingConfig = signingConfigs.getByName("debug")
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
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-text")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    implementation(project(":core-editor"))
    implementation(project(":core-indexer"))
    implementation(project(":core-compiler"))
    implementation(project(":core-storage"))
}
