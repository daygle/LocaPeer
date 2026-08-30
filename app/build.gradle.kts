plugins {
    alias(libs.plugins.android.application)
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
    // Compose
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.material)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // Core AndroidX & Lifecycle
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    // Navigation & DI
    implementation(libs.androidx.navigation.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Networking & Crypto
    implementation(libs.okhttp)
    implementation(libs.secp256k1)
    implementation(libs.bcprov)

    // Location, Maps, QR, Camera
    implementation(libs.play.services.location)
    implementation(libs.osmdroid)
    implementation(libs.zxing.android.embedded) { isTransitive = false }
    implementation(libs.zxing.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
    implementation(libs.androidx.biometric)
    implementation(libs.accompanist.permissions)

    // Kotlin Utilities
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Security Hardening
    constraints {
        implementation(libs.netty.codec.http2) { because("CVE-2023-44487") }
        implementation(libs.jdom2) { because("CVE-2021-33813") }
        implementation(libs.httpclient) { because("CVE-2020-13956") }
        implementation(libs.jose4j) { because("CVE-2023-31582") }
        implementation(libs.commons.lang3) { because("CVE-2022-42889") }
        implementation(libs.bcpkix) { because("Security fixes in latest Bouncy Castle") }
        implementation(libs.bcutil)
        implementation(libs.bcprov)
    }

    // Testing
    testImplementation(libs.junit)
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
