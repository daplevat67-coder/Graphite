import java.util.Properties
import java.security.KeyStore
import java.security.MessageDigest

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
val signingFile = rootProject.file(System.getenv("GRAPHITE_SIGNING_PROPERTIES") ?: "signing.properties")
val signingValues = Properties().apply { if(signingFile.isFile) signingFile.inputStream().use { load(it) } }
val pinnedCertificate = "edfeabe4954ad258f1695a7623ff11b9cff66633bdec4eee89931a9a118d924e"
val verifyStableSigning = tasks.register("verifyStableSigning") {
    doLast {
        check(signingFile.isFile) { "Missing signing.properties: restore the existing GRAPHITE signing key. See SIGNING.md" }
        val store = rootProject.file(signingValues.getProperty("storeFile"))
        check(store.isFile) { "Signing key is missing; do not generate a replacement." }
        val ks = KeyStore.getInstance("PKCS12")
        store.inputStream().use { ks.load(it, signingValues.getProperty("storePassword").toCharArray()) }
        val certificate = ks.getCertificate(signingValues.getProperty("keyAlias"))
        check(ks.isKeyEntry(signingValues.getProperty("keyAlias"))) { "Private signing key is missing" }
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it.toInt() and 255) }
        check(digest == pinnedCertificate) { "Wrong signing certificate; updates would fail." }
    }
}
android {
    namespace = "art.graphite.vpn"
    compileSdk = 35
    defaultConfig {
        applicationId = "art.graphite.vpn"
        minSdk = 26
        targetSdk = 35
        versionCode = 17
        versionName = "0.3.1-alpha1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    signingConfigs.getByName("debug") {
        storeFile = rootProject.file(signingValues.getProperty("storeFile", "MISSING_SIGNING_KEY.p12"))
        storePassword = signingValues.getProperty("storePassword", "")
        keyAlias = signingValues.getProperty("keyAlias", "")
        keyPassword = signingValues.getProperty("keyPassword", "")
        storeType = "PKCS12"
    }
    packaging { jniLibs.useLegacyPackaging = true }
    buildFeatures { compose = true }
    testOptions { unitTests.isIncludeAndroidResources = true }
    compileOptions { isCoreLibraryDesugaringEnabled = true; sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    implementation(project(":awg-tunnel"))
    implementation(project(":xray-tunnel"))
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    testImplementation("androidx.compose.ui:ui-test-junit4")
    testImplementation("org.robolectric:robolectric:4.14.1")
    implementation(platform("androidx.compose:compose-bom:2025.04.01"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.04.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test:runner:1.6.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}

// Forward only task-local network settings for Robolectric SDK retrieval on hosted builds.
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    maxHeapSize = "2g"
    maxParallelForks = 1
    systemProperty("robolectric.dependency.repo.url", "https://repo.maven.apache.org/maven2")
    listOf("http.proxyHost", "http.proxyPort", "https.proxyHost", "https.proxyPort", "javax.net.ssl.trustStore").forEach { key ->
        System.getProperty(key)?.let { systemProperty(key,it) }
    }
}

tasks.matching { it.name == "validateSigningDebug" || it.name == "validateSigningDebugAndroidTest" }.configureEach { dependsOn(verifyStableSigning) }
