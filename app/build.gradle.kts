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
        versionCode = 4
        versionName = "1.3.0"
    }
}

dependencies {
    implementation("com.google.zxing:core:3.5.3")
}
