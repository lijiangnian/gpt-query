plugins {
    id("com.android.application")
}

android {
    namespace = "com.vsme.vlinkconverter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.vsme.vlinkconverter"
        minSdk = 24
        targetSdk = 35
        versionCode = 3
        versionName = "1.2.0"
    }
}

dependencies {
    implementation("com.google.zxing:core:3.5.3")
}
