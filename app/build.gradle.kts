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
        versionCode = 5
        versionName = "1.0.5"
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
            isMinifyEnabled = false // off to avoid R8/ProGuard release crash
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.slidingpanelayout:slidingpanelayout:1.2.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("org.commonmark:commonmark:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-tables:0.24.0")
    implementation("org.commonmark:commonmark-ext-gfm-strikethrough:0.24.0")
    implementation("org.commonmark:commonmark-ext-task-list-items:0.24.0")
    implementation("org.jsoup:jsoup:1.18.1")
}
