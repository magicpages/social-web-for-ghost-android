import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.aboutlibraries)
    alias(libs.plugins.baselineprofile)
}

// Upload-key credentials come from keystore.properties (gitignored) or SOCIALWEB_* environment
// variables. Without them release stays unsigned, so debug, benchmark and CI builds keep working.
val keystoreProperties = Properties().apply {
    providers.fileContents(rootProject.layout.projectDirectory.file("keystore.properties"))
        .asText.orNull?.let { load(it.reader()) }
}

fun signingValue(property: String, environment: String): String? =
    keystoreProperties.getProperty(property)?.takeIf { it.isNotBlank() }
        ?: providers.environmentVariable(environment).orNull?.takeIf { it.isNotBlank() }

android {
    namespace = "co.magicpages.socialweb"
    compileSdk = 37

    defaultConfig {
        applicationId = "co.magicpages.socialweb"
        minSdk = 29
        targetSdk = 37
        // CI sets SOCIALWEB_VERSION_CODE (the commit count on main) for each Play upload.
        versionCode = providers.environmentVariable("SOCIALWEB_VERSION_CODE").orNull?.toInt() ?: 1
        versionName = "0.1.0"
        manifestPlaceholders["appLabel"] = "Social Web"
    }

    val uploadStore = signingValue("storeFile", "SOCIALWEB_KEYSTORE_FILE")?.let { rootProject.file(it) }
    val uploadStorePassword = signingValue("storePassword", "SOCIALWEB_KEYSTORE_PASSWORD")
    val uploadKeyAlias = signingValue("keyAlias", "SOCIALWEB_KEY_ALIAS")
    if (uploadStore?.isFile == true && uploadStorePassword != null && uploadKeyAlias != null) {
        signingConfigs {
            create("release") {
                storeFile = uploadStore
                storePassword = uploadStorePassword
                keyAlias = uploadKeyAlias
                keyPassword = signingValue("keyPassword", "SOCIALWEB_KEY_PASSWORD") ?: uploadStorePassword
            }
        }
    }

    buildTypes {
        // Installs next to the store app (which Play signs with another key) instead of clashing with
        // it, with its own data and sign-ins, and says so under its icon.
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            manifestPlaceholders["appLabel"] = "Social Web (dev)"
        }
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        // The store build's speed (R8, no debuggable flag) for trying things on a phone. Signed with
        // the debug key under debug's package, so it installs over a debug build and keeps its data.
        // No demo mode: that lives in src/debug.
        create("dev") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-dev"
            manifestPlaceholders["appLabel"] = "Social Web (dev)"
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    // Lists the app's languages for the system's per-app language setting (Android 13+).
    androidResources {
        generateLocaleConfig = true
    }
}

// The baseline profile is recorded against the demo mode, which lives in src/debug so that the
// store build never carries it. The plugin's profiling variants (nonMinifiedRelease for the profile,
// benchmarkRelease for the startup benchmark) are copies of release, so they would start at the
// login screen without it: give them the demo sources, assets and manifest from src/debug (but not
// its res, which only loosens cleartext rules for local servers). `release`, and with it the
// bundle, still compiles without any of this. finalizeDsl runs after the plugin has created the
// variants and copied release's source sets into them.
androidComponents {
    finalizeDsl { extension ->
        listOf("nonMinifiedRelease", "benchmarkRelease").forEach { name ->
            extension.sourceSets.getByName(name).apply {
                kotlin.directories += "src/debug/kotlin"
                assets.directories += "src/debug/assets"
                manifest.srcFile("src/debug/AndroidManifest.xml")
            }
            // These variants hold the demo code: never sign them with the upload key.
            extension.buildTypes.getByName(name).signingConfig = extension.signingConfigs.getByName("debug")
        }
    }
}

baselineProfile {
    // The recorded run includes demo classes. Their rules stay in both profile files (the plugin's
    // `filter { exclude(...) }` left them in, 1.5.0); ART and R8 skip rules for classes the release doesn't have.
    // Uses startup-prof.txt to lay out the primary dex with the classes startup needs.
    dexLayoutOptimization = true
}

dependencies {
    implementation(libs.androidx.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.paging.compose)
    implementation(libs.androidx.datastore)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.profileinstaller)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.navigation.suite)
    implementation(libs.compose.material3.adaptive.navigation3)
    implementation(libs.materialkolor)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.coil.compose)
    implementation(libs.coil.network.ktor)
    implementation(libs.coil.gif)
    implementation(libs.coil.svg)
    implementation(libs.tink.android)
    implementation(libs.jsoup)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui.compose)

    implementation(libs.mlkit.translate)
    implementation(libs.mlkit.language.id)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.aboutlibraries.compose.m3)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)

    baselineProfile(project(":baselineprofile"))
}
