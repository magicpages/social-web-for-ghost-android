plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "co.magicpages.socialweb.baselineprofile"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Macrobenchmark refuses to measure on an emulator; the startup comparison is still worth
        // running there, as relative numbers. Real devices don't report the error, so this changes nothing on them.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The same store flavors as :app, so each of its release variants has a producer to run against.
    flavorDimensions += "store"
    productFlavors {
        create("play") { dimension = "store" }
        create("foss") { dimension = "store" }
    }

    targetProjectPath = ":app"
}

baselineProfile {
    // Generate on whatever device or emulator adb sees; no Gradle Managed Device to download.
    useConnectedDevices = true
    // The plugin skips benchmarks on emulators by default; keep them running so the startup
    // comparison can be read from the emulator the profile is generated on.
    skipBenchmarksOnEmulator = false
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
