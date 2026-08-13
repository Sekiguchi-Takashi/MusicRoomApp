plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.appathy.musicroom"
    compileSdk = 34

    signingConfigs {
        create("appathy") {
            storeFile = rootProject.file("keystore/musicroom.jks")
            storePassword = "musicroom"
            keyAlias = "musicroom"
            keyPassword = "musicroom"
        }
    }

    defaultConfig {
        applicationId = "com.appathy.musicroom"
        minSdk = 26
        targetSdk = 34
        versionCode = 11
        versionName = "1.10"
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("appathy")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("appathy")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
