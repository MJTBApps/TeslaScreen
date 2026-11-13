plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    // Identificador provisional (sin «Tesla»: Google Play no admite marcas ajenas).
    // Se puede cambiar hasta la primera subida a Google Play.
    namespace = "es.mjtbapps.espejocoche"
    compileSdk = 36

    defaultConfig {
        applicationId = "es.mjtbapps.espejocoche"
        minSdk = 30
        targetSdk = 36
        versionCode = 8
        versionName = "0.2.1"
        // Solo móviles de 64 bits (casi todos) y el emulador: la APK pesa la mitad.
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
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
    // WebRTC (compilación de libwebrtc de Stream, en Maven Central).
    implementation("io.getstream:stream-webrtc-android:1.3.10")
    // WebSocket para el emparejamiento.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
