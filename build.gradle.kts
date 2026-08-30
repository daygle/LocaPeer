buildscript {
    repositories {
        google()
        mavenCentral()
    }
    configurations.all {
        resolutionStrategy {
            eachDependency {
                if (requested.group == "org.bouncycastle" && (requested.name.startsWith("bcprov-jdk") || requested.name.startsWith("bcpkix-jdk") || requested.name.startsWith("bcutil-jdk"))) {
                    val artifact = if (requested.name.startsWith("bcprov")) "bcprov-jdk18on" else if (requested.name.startsWith("bcpkix")) "bcpkix-jdk18on" else "bcutil-jdk18on"
                    val version = if (artifact == "bcprov-jdk18on") "1.85.2" else "1.85"
                    useTarget("org.bouncycastle:$artifact:$version")
                }
                if (requested.group == "io.netty" && requested.version != null && requested.version!!.startsWith("4.1.")) {
                    useVersion("4.1.137.Final")
                }
                if (requested.group == "org.jdom" && requested.name == "jdom2") {
                    useVersion("2.0.6.1")
                }
                // AGP pulls commons-lang3 (via com.android.tools:repository -> commons-compress)
                // and jose4j (via bundletool) into the build classpath at vulnerable versions.
                if (requested.group == "org.apache.commons" && requested.name == "commons-lang3") {
                    useVersion("3.20.0")
                    because("CVE-2025-48924: uncontrolled recursion DoS in ClassUtils.getClass")
                }
                if (requested.group == "org.bitbucket.b_c" && requested.name == "jose4j") {
                    useVersion("0.9.6")
                    because("CVE-2024-29371: DoS via compressed JWE content")
                }
            }
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    // The three Kotlin plugin versions float with the `kotlinVersion` Gradle property
    // (default 2.4.20-RC in gradle.properties). settings.gradle.kts declares them in
    // pluginManagement.plugins { }, and the CodeQL workflow overrides the property with
    // -PkotlinVersion (e.g. 2.4.10) because its Kotlin extractor hard-rejects newer
    // releases. Pinning an explicit version here would bypass that default and break
    // the override, so they must stay version-less.
    id("org.jetbrains.kotlin.android") apply false
    id("org.jetbrains.kotlin.plugin.compose") apply false
    id("org.jetbrains.kotlin.plugin.serialization") apply false
    id("com.google.dagger.hilt.android") version "2.60.1" apply false
    id("com.google.devtools.ksp") version "2.3.11" apply false
}
