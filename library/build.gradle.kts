// AndrOBD library module — только протокол ELM327/OBD2
// Источник: https://github.com/fr3ts0n/AndrOBD (GPLv2)
// Директория содержится как vendored код, не submodule

plugins {
    id("com.android.library")
}

android {
    namespace = "com.fr3ts0n.ecu.prot"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        targetSdk = 34
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("com")
        }
    }
}

dependencies {
    implementation("androidx.annotation:annotation:1.7.0")
}
