plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sp.textextract"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.sp.textextract"
        minSdk = 27
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
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
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }

    buildFeatures {
        compose      = true
        viewBinding  = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.4.3" }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    // ── Core AndroidX ──────────────────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.navigation:navigation-fragment-ktx:2.5.3")
    implementation("androidx.navigation:navigation-ui-ktx:2.5.3")

    // ── Compose ────────────────────────────────────────────────────────────────
    implementation(platform("androidx.compose:compose-bom:2023.03.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // ── Material ───────────────────────────────────────────────────────────────
    implementation("com.google.android.material:material:1.13.0")

    // ── EXIF reading (for auto-rotation) ───────────────────────────────────────
    // Reads JPEG orientation tag so the preprocessor can rotate before cropping.
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // ── Coroutines (preprocessing on background thread) ───────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ── OpenCV (image preprocessing pipeline) ─────────────────────────────────
    // The official OpenCV Android SDK AAR published to Maven Central.
    // Contains the full native .so for arm64-v8a, armeabi-v7a, x86, x86_64.
    implementation("org.opencv:opencv:4.9.0")

    // ── ML Kit OCR ─────────────────────────────────────────────────────────────
    implementation("com.google.mlkit:text-recognition:16.0.0")

    // ── Tests ──────────────────────────────────────────────────────────────────
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2023.03.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}