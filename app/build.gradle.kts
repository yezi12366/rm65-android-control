plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.realman.rm65webview"
    compileSdk = 35

    defaultConfig {
        val sideBySide = providers.gradleProperty("sideBySide").orNull == "true"
        applicationId = if (sideBySide) "com.realman.rm65webview.trial1154" else "com.realman.rm65webview"
        manifestPlaceholders["appLabel"] = if (sideBySide) "RM65 1.15.4 试用版" else "RM65 网页直连遥控台"
        minSdk = 26
        targetSdk = 35
        versionCode = 30
        versionName = "1.15.4"
    }

    // Reuse this workstation's installation key when available; exported source builds use the normal default.
    val localDebugKeystore = rootProject.file("../.android-signing/rm65-debug.keystore")
    if (localDebugKeystore.isFile) {
        signingConfigs.getByName("debug").storeFile = localDebugKeystore
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
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
