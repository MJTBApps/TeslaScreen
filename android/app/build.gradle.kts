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
        versionCode = 3
        versionName = "0.1.2"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
