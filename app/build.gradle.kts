plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ejemplo.visor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ejemplo.visor"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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
        viewBinding = false
    }
}

dependencies {
    // AndroidX core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")

    // Material Design
    implementation("com.google.android.material:material:1.11.0")

    // ConstraintLayout (por si lo usas en layouts)
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // WebView moderno (DOCUMENT_START_SCRIPT, ALGORITHMIC_DARKENING, etc.)
    implementation("androidx.webkit:webkit:1.10.0")

    // Pull to refresh
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")

    // Corrutinas (opcional pero útil)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
