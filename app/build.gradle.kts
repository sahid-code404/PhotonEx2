plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val devKeystore = rootProject.file("tools/dev-signing/photonex2-dev.jks")
val versionCodeFromEnv = providers.environmentVariable("PHOTONEX2_VERSION_CODE").orElse("1")
val versionNameFromEnv = providers.environmentVariable("PHOTONEX2_VERSION_NAME").orElse("0.1.0-dev")
val signerSha = rootProject.file("tools/dev-signing/EXPECTED_CERT_SHA256")
    .takeIf { it.isFile }
    ?.readText()
    ?.trim()
    .orEmpty()

android {
    namespace = "com.sahidcode404.photonex2"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.sahidcode404.photonex2"
        minSdk = 23
        targetSdk = 35
        versionCode = versionCodeFromEnv.get().toInt()
        versionName = versionNameFromEnv.get()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
        buildConfigField("String", "DEV_SIGNER_SHA256", "\"$signerSha\"")
        buildConfigField("String", "DEV_MANIFEST_URL", "\"https://github.com/sahid-code404/PhotonEx2/releases/download/dev-latest/dev-manifest.json\"")
    }

    signingConfigs {
        create("devOta") {
            if (devKeystore.isFile) {
                storeFile = devKeystore
                storePassword = "photonex2dev"
                keyAlias = "photonex2-dev"
                keyPassword = "photonex2dev"
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        create("devOta") {
            initWith(getByName("debug"))
            applicationIdSuffix = null
            versionNameSuffix = null
            isDebuggable = true
            signingConfig = signingConfigs.getByName("devOta")
            matchingFallbacks += listOf("debug")
        }
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions.jvmTarget = "17"

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging.resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.01.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
