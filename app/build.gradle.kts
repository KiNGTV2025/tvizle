plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.umitm0d.tvizle"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.umitm0d.tvizle"
        minSdk = 21
        targetSdk = 30
        versionCode = 2
        versionName = "1.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
