plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.unstko.garminexport"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.unstko.garminexport"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.2")

    testImplementation("junit:junit:4.13.2")
    // Android ships org.json; plain JVM unit tests need the real thing.
    testImplementation("org.json:json:20240303")
}
