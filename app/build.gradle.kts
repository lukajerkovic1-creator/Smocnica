import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val localProperties = Properties().apply {
    val propertiesFile = rootProject.file("local.properties")
    if (propertiesFile.exists()) propertiesFile.inputStream().use(::load)
}
fun releaseSecret(localName: String, environmentName: String): String? =
    localProperties.getProperty(localName)?.takeIf { it.isNotBlank() }
        ?: System.getenv(environmentName)?.takeIf { it.isNotBlank() }

val releaseStoreFile = releaseSecret("smocnica.release.storeFile", "ANDROID_KEYSTORE_FILE")
val releaseStorePassword = releaseSecret("smocnica.release.storePassword", "ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = releaseSecret("smocnica.release.keyAlias", "ANDROID_KEY_ALIAS")
val releaseKeyPassword = releaseSecret("smocnica.release.keyPassword", "ANDROID_KEY_PASSWORD")
val releaseSigningValues = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
val configuredReleaseSigningValues = releaseSigningValues.count { !it.isNullOrBlank() }
require(configuredReleaseSigningValues == 0 || configuredReleaseSigningValues == releaseSigningValues.size) {
    "Release signing je djelomično konfiguriran. Postavite sva četiri smocnica.release.* svojstva u local.properties ili sva četiri ANDROID_* environment secreta."
}
val hasReleaseSigning = configuredReleaseSigningValues == releaseSigningValues.size
val releaseKeystore = releaseStoreFile?.let { rootProject.file(it) }

val hasFirebaseConfig = listOf(
    file("google-services.json"),
    file("src/debug/google-services.json"),
    file("src/release/google-services.json"),
).any { it.exists() }

if (hasFirebaseConfig) {
    apply(plugin = "com.google.gms.google-services")
    apply(plugin = "com.google.firebase.crashlytics")
}

android {
    namespace = "hr.smocnica"
    compileSdk = 37

    defaultConfig {
        applicationId = "hr.smocnica"
        minSdk = 29
        targetSdk = 37
        versionCode = providers.gradleProperty("VERSION_CODE").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("VERSION_NAME").orNull ?: "1.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "FIREBASE_EMULATORS", (providers.gradleProperty("FIREBASE_EMULATORS").orNull == "true").toString())
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    signingConfigs {
        if (hasReleaseSigning) create("release") {
            storeFile = requireNotNull(releaseKeystore)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

tasks.register("verifyReleaseSigningConfiguration") {
    group = "verification"
    description = "Zaustavlja produkcijski build ako release signing nije potpun ili keystore ne postoji."
    doLast {
        check(hasReleaseSigning) {
            "Release signing nije konfiguriran. Koristite ignorirani local.properties ili ANDROID_* GitHub Actions Secrets."
        }
        check(requireNotNull(releaseKeystore).isFile) {
            "Release keystore ne postoji na konfiguriranoj putanji: ${requireNotNull(releaseKeystore).path}"
        }
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:domain"))
    implementation(project(":core:data"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    implementation(libs.androidx.work)
    ksp(libs.hilt.work.compiler)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play)
    implementation(libs.google.identity)
    implementation(libs.coil.compose)
    implementation(libs.coil.network)
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.mlkit.barcode)
    implementation(libs.zxing.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.functions)
    implementation(libs.firebase.storage)
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.appcheck.playintegrity)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.espresso.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.firebase.appcheck.debug)
}
