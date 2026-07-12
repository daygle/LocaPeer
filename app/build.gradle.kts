plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.add("-Xannotation-default-target=param-property")
    }
}

android {
    namespace = "com.locapeer"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.locapeer"
        minSdk = 26
        targetSdk = 37
        versionCode = providers.gradleProperty("appVersionCode").getOrElse("1").toInt()
        versionName = providers.gradleProperty("appVersionName").getOrElse("1.0")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    lint {
        // Project has 50+ locales. Don't block CI for translation/plural nits.
        disable += "MissingTranslation"
        disable += "ImpliedQuantity"
        disable += "MissingQuantity"

        // Ensure we still fail on other real code errors
        abortOnError = true
        // Uploaded as artifact in CI
        htmlReport = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            ndk {
                debugSymbolLevel = "SYMBOL_TABLE"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // Let JVM unit tests touch stubbed android.* APIs (e.g. Log) without crashing
        unitTests.isReturnDefaultValues = true
    }

    // Room writes KSP-exported schema JSONs to app/schemas/ at build time. The instrumented
    // MigrationTest needs those files inside the test APK's assets/ folder so
    // MigrationTestHelper.createDatabase(name, version) can replay an exact historical
    // schema before running migrations forward - otherwise the tests fail with
    // FileNotFoundException for com.locapeer.data.AppDatabase/{N}.json.
    sourceSets {
        named("androidTest") {
            assets.directories.add("schemas")
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
            keepDebugSymbols += "**/libandroidx.graphics.path.so"
            keepDebugSymbols += "**/libdatastore_shared_counter.so"
            keepDebugSymbols += "**/libimage_processing_util_jni.so"
            keepDebugSymbols += "**/libsecp256k1-jni.so"
            keepDebugSymbols += "**/libsurface_util_jni.so"
        }
        resources {
            excludes += "META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "mozilla/public-suffix-list.txt"
        }
    }
}

hilt {
    enableAggregatingTask = true
}

ksp {
    // Room writes a JSON snapshot of each schema version here on build.
    // Commit them: migrations are written and tested against these files.
    arg("room.schemaLocation", "${layout.projectDirectory.dir("schemas").asFile.path}")
}

dependencies {
    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2026.06.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Core AndroidX
    implementation("androidx.core:core-ktx:1.19.0")
    // AppCompat provides the per-app language backport (AppCompatDelegate.setApplicationLocales)
    // for API < 33; on API 33+ it delegates to the framework LocaleManager.
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-service:2.11.0")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.9.8")

    // Biometric / device-credential prompt for the optional app-lock screen
    implementation("androidx.biometric:biometric:1.1.0")
    // ProcessLifecycleOwner for "app actually backgrounded" events (vs. per-Activity
    // ON_STOP, which fires on rotation and transient system dialogs)
    implementation("androidx.lifecycle:lifecycle-process:2.11.0")

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.60.1")
    ksp("com.google.dagger:hilt-compiler:2.60.1")
    implementation("com.google.errorprone:error_prone_annotations:2.50.0")
    implementation("androidx.hilt:hilt-navigation-compose:1.4.0")
    implementation("androidx.hilt:hilt-work:1.4.0")
    ksp("androidx.hilt:hilt-compiler:1.4.0")

    // Room
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.11.2")

    // Location + Activity Recognition
    implementation("com.google.android.gms:play-services-location:21.4.0")

    // OkHttp WebSocket
    implementation("com.squareup.okhttp3:okhttp:5.4.0")

    // secp256k1 crypto (ACINQ KMP)
    implementation("fr.acinq.secp256k1:secp256k1-kmp-jni-android:0.23.0")

    // Bouncy Castle for fallback crypto (AES, SHA, ECDH helpers).
    // jdk18on is the maintained artifact line; 1.70/jdk15on (2021) is EOL and carries
    // published CVEs. Only the low-level crypto.* primitives (SHA-256, ChaCha20, HKDF,
    // HMAC) are used here, and their API is stable across these versions.
    implementation("org.bouncycastle:bcprov-jdk18on:1.85")

    // QR Code
    implementation("com.journeyapps:zxing-android-embedded:4.3.0") { isTransitive = false }
    implementation("com.google.zxing:core:3.5.4")

    // OSMDroid map
    implementation("org.osmdroid:osmdroid-android:6.1.20")

    // DataStore Preferences
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // Kotlin Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")

    // Kotlin Serialization
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    // CameraX for QR scanning
    val cameraVersion = "1.6.1"
    implementation("androidx.camera:camera-camera2:$cameraVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraVersion")
    implementation("androidx.camera:camera-view:$cameraVersion")

    // Accompanist permissions
    implementation("com.google.accompanist:accompanist-permissions:0.37.3")

    // Unit tests
    testImplementation("junit:junit:4.13.2")
    // Mockito for limited mocked-AppPreferences coverage of AppLockManager (only the
    // unlocked StateFlow default + setUnlocked() flip is meaningful without a real
    // DataStore; lifecycle observer and pref-driven coroutines stay covered by inspection).
    testImplementation("org.mockito:mockito-core:5.23.0")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    androidTestImplementation("androidx.room:room-testing:2.8.4")
}
