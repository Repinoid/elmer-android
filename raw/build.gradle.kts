plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.elmer.raw"
    compileSdk = 34

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("fixed") {
            storeFile = rootProject.file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    defaultConfig {
        applicationId = "ru.elmer.raw"
        minSdk = 24
        targetSdk = 34
        versionCode = 6
        versionName = "0.2.2-dev"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            buildConfigField("String", "API_KEY", "\"${project.findProperty("ELMER_API_KEY") ?: ""}\"")
            buildConfigField("String", "SERVER_URL", "\"https://obdai.ru\"")
            buildConfigField("String", "VERSION_NAME", "\"0.1.1-dev\"")
        }
        debug {
            signingConfig = signingConfigs.getByName("fixed")
            buildConfigField("String", "API_KEY", "\"${project.findProperty("ELMER_API_KEY") ?: ""}\"")
            buildConfigField("String", "SERVER_URL", "\"https://obdai.ru\"")
            buildConfigField("String", "VERSION_NAME", "\"0.1.1-dev\"")
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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20231013")
}
