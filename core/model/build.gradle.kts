plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.serialization)
}

android {
    namespace = "hr.smocnica.core.model"
    compileSdk = 37

    defaultConfig { minSdk = 29 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    api(libs.kotlinx.serialization.json)
}
