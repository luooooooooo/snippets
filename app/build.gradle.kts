plugins {
    id("com.android.application")
}

android {
    namespace = "com.kvelzer.snippets"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kvelzer.snippets"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            val ksFile = providers.gradleProperty("SNIPPETS_STORE_FILE").orNull
            if (ksFile != null) {
                storeFile = file(ksFile)
                storePassword = providers.gradleProperty("SNIPPETS_STORE_PASSWORD").get()
                keyAlias = providers.gradleProperty("SNIPPETS_KEY_ALIAS").get()
                keyPassword = providers.gradleProperty("SNIPPETS_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = if (providers.gradleProperty("SNIPPETS_STORE_FILE").orNull != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
}
