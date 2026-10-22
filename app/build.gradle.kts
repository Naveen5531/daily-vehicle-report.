import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
// Firebase project settings (sign-in, cloud copy). Without the file the app builds in "this phone only" mode.
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

android {
    namespace = "com.logistics.vehiclereport"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.logistics.vehiclereport"
        minSdk = 26
        // Google Play requires Android 16 (API 36) for new apps and updates from 31 Aug 2026.
        targetSdk = 36
        // Every GitHub build gets a higher number, so phones always accept it as an update.
        val run = (System.getenv("GITHUB_RUN_NUMBER") ?: "0").toIntOrNull() ?: 0
        versionCode = 1000 + run
        versionName = "6.1.$run"
    }

    // Fixed key so every GitHub build installs as an update over the previous one.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Play Store upload key (Google re-signs the app for users with its own key).
        create("upload") {
            val props = Properties()
            val f = file("upload.properties")
            if (f.exists()) f.inputStream().use { props.load(it) }
            storeFile = file("upload.jks")
            storePassword = props.getProperty("password", "")
            keyAlias = "dvr-upload"
            keyPassword = props.getProperty("password", "")
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("upload")
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
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    // Sign-in (Google account or email) and each account's own cloud copy of its data
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.biometric:biometric:1.1.0")
    // Camera scan of a number plate, read on the phone (nothing is sent anywhere)
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2")
    // CameraX hands back a ListenableFuture (Guava is already in the app through Firebase)
    implementation("com.google.guava:guava:33.3.1-android")
    // Text reader from Google Play services: keeps the app small (the reader is downloaded once by Play services)
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")
    // Map on the Plan tab: OpenStreetMap tiles, no API key or sign-in needed
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    testImplementation("junit:junit:4.13.2")
}
