plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
    id("org.jetbrains.kotlin.plugin.serialization")
}

kotlin {
    jvmToolchain(17)
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
                debugSymbolLevel = "FULL"
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
            // Silence "Unable to strip" warnings for pre-stripped 3rd party libs
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
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.material)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // Core AndroidX
    implementation(libs.androidx.core.ktx)
    // AppCompat provides the per-app language backport (AppCompatDelegate.setApplicationLocales)
    // for API < 33; on API 33+ it delegates to the framework LocaleManager.
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    // Navigation
    implementation(libs.androidx.navigation.compose)

    // Biometric / device-credential prompt for the optional app-lock screen
    implementation(libs.androidx.biometric)
    // ProcessLifecycleOwner for "app actually backgrounded" events (vs. per-Activity
    // ON_STOP, which fires on rotation and transient system dialogs)
    implementation(libs.androidx.lifecycle.process)

    // Hilt DI
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // WorkManager
    implementation(libs.androidx.work.runtime.ktx)

    // Location + Activity Recognition
    implementation(libs.play.services.location)

    // OkHttp WebSocket
    implementation(libs.okhttp)

    // secp256k1 crypto (ACINQ KMP)
    implementation(libs.secp256k1)

    // Bouncy Castle for fallback crypto (AES, SHA, ECDH helpers).
    // jdk18on is the maintained artifact line; 1.70/jdk15on (2021) is EOL and carries
    // published CVEs. Only the low-level crypto.* primitives (SHA-256, ChaCha20, HKDF,
    // HMAC) are used here, and their API is stable across these versions.
    implementation(libs.bcprov)

    // QR Code
    implementation(libs.zxing.android.embedded) { isTransitive = false }
    implementation(libs.zxing.core)

    // OSMDroid map
    implementation(libs.osmdroid)

    // DataStore Preferences
    implementation(libs.androidx.datastore.preferences)

    // Kotlin Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Kotlin Serialization
    implementation(libs.kotlinx.serialization.json)

    // Security Hardening: Force safe versions for transitive dependencies
    constraints {
        implementation(libs.netty.codec.http2) {
            because("CVE-2023-44487 (Rapid Reset) and other Netty vulnerabilities")
        }
        implementation(libs.jdom2) {
            because("CVE-2021-33813 (XXE vulnerability)")
        }
        implementation(libs.httpclient) {
            because("CVE-2020-13956 and other HttpClient vulnerabilities")
        }
        implementation(libs.jose4j) {
            because("CVE-2023-31582 (DoS via compressed JWE)")
        }
        implementation(libs.commons.lang3) {
            because("CVE-2022-42889 (Recursion vulnerability)")
        }
        implementation(libs.bcpkix) {
            because("Security fixes in latest Bouncy Castle")
        }
        implementation(libs.bcutil)
        implementation(libs.bcprov)
    }

    // CameraX for QR scanning
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    // Accompanist permissions
    implementation(libs.accompanist.permissions)

    // Unit tests
    testImplementation(libs.junit)
    // Mockito for limited mocked-AppPreferences coverage of AppLockManager (only the
    // unlocked StateFlow default + setUnlocked() flip is meaningful without a real
    // DataStore; lifecycle observer and pref-driven coroutines stay covered by inspection).
    testImplementation(libs.mockito.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.room.testing)
}

// Global resolution strategy to align versions and swap vulnerable artifact lines
configurations.all {
    resolutionStrategy {
        eachDependency {
            // Force all Bouncy Castle artifacts to the jdk18on line and latest version
            if (requested.group == "org.bouncycastle" && (requested.name.startsWith("bcprov-jdk") || requested.name.startsWith("bcpkix-jdk") || requested.name.startsWith("bcutil-jdk"))) {
                val artifact = if (requested.name.startsWith("bcprov")) "bcprov-jdk18on" else if (requested.name.startsWith("bcpkix")) "bcpkix-jdk18on" else "bcutil-jdk18on"
                val version = if (artifact == "bcprov-jdk18on") "1.85.2" else "1.85"
                useTarget("org.bouncycastle:$artifact:$version")
                because("Consolidate on maintained jdk18on artifact line and patch critical vulnerabilities")
            }
            // Align all Netty modules to a safe version
            if (requested.group == "io.netty" && requested.version != null && requested.version!!.startsWith("4.1.")) {
                useVersion("4.1.137.Final")
                because("Apply security patches for HTTP/2 Rapid Reset and other vulnerabilities")
            }
            // Force latest versions for other vulnerable components
            if (requested.group == "org.jdom" && requested.name == "jdom2") {
                useVersion("2.0.6.1")
            }
            if (requested.group == "org.apache.httpcomponents" && requested.name == "httpclient") {
                useVersion("4.5.14")
            }
            if (requested.group == "org.bitbucket.b_c" && requested.name == "jose4j") {
                useVersion("0.9.6")
            }
            if (requested.group == "org.apache.commons" && requested.name == "commons-lang3") {
                useVersion("3.20.0")
            }
        }
    }
}
