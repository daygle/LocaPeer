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
            }
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.hilt.android) apply false
    id("com.google.devtools.ksp") version "2.3.11" apply false
}
