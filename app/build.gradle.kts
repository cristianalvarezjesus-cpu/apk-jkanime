plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Si el workflow de GitHub Actions entrega un keystore (vía Secrets), se firma el release con él.
val ciKeystorePath: String? = System.getenv("KEYSTORE_PATH")
val hasCiKeystore: Boolean = ciKeystorePath != null && file(ciKeystorePath).exists()

android {
    namespace = "com.ejemplo.visor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ejemplo.visor"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        if (hasCiKeystore) {
            create("release") {
                storeFile = file(ciKeystorePath!!)
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
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
            if (hasCiKeystore) {
                signingConfig = signingConfigs.getByName("release")
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.webkit:webkit:1.11.0")
}
