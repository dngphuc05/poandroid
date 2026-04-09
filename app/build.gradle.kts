plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pocketmocap.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.pocketmocap.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "2.0.0"

