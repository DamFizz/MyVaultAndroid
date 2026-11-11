import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
    // Argon2id only (Argon2BytesGenerator); AES-GCM, HMAC and SHA-256 use the platform JCA.
    implementation("org.bouncycastle:bcprov-jdk18on:1.79")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
    // Where the Kotlin->web fixture is written for tools/webfixture to verify.
    systemProperty("myvault.fixtureOut", rootProject.file("tools/webfixture/out").absolutePath)
    systemProperty("myvault.fixtureIn", rootProject.file("core/src/test/resources/fixtures").absolutePath)
    testLogging { events("passed", "failed", "skipped"); showStandardStreams = true; exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL }
}
