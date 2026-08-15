plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.appathy.musicroom"
    compileSdk = 34

    // 配布用の鍵 (ci/appathy.keystore) があればそれを使う。
    // ローカル/フォールバックは同梱の keystore/musicroom.jks。
    // どちらも「毎回同じ鍵で署名して上書きインストールできること」が目的。
    val ciKeystore = rootProject.file("ci/appathy.keystore")
    val ciStorePassword: String? = System.getenv("APPATHY_STORE_PASSWORD")
        ?: findProperty("appathyStorePassword") as String?
    val useCiKeystore = ciKeystore.exists() && ciStorePassword != null

    signingConfigs {
        create("appathy") {
            if (useCiKeystore) {
                storeFile = ciKeystore
                storePassword = ciStorePassword
                keyAlias = System.getenv("APPATHY_KEY_ALIAS")
                    ?: (findProperty("appathyKeyAlias") as String? ?: "appathy")
                keyPassword = System.getenv("APPATHY_KEY_PASSWORD")
                    ?: (findProperty("appathyKeyPassword") as String? ?: ciStorePassword)
            } else {
                storeFile = rootProject.file("keystore/musicroom.jks")
                storePassword = "musicroom"
                keyAlias = "musicroom"
                keyPassword = "musicroom"
            }
        }
    }

    defaultConfig {
        applicationId = "com.appathy.musicroom"
        minSdk = 26
        targetSdk = 34
        versionCode = 14
        versionName = "1.13"
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
