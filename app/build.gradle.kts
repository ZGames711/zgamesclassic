import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.net.URL
import java.net.HttpURLConnection
import java.util.zip.ZipInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.evolutionofgames.zgamespsx"
    compileSdkVersion("android-37.0")

    defaultConfig {
        applicationId = "com.evolutionofgames.zgamespsx"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0"
        // AdMob App ID: replace with the App ID of THIS package (com.evolutionofgames.zgamespsx).
        // Ad unit IDs can be changed from the WordPress plugin without an update.
        manifestPlaceholders["admobAppId"] = "ca-app-pub-5936725114299330~8942663706"
        // The built-in PS1 emulator (libretro frontend + core) is 64-bit only.
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                // The libretro core may need libc++_shared.so: bundle it.
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    // Builds src/main/cpp (the libretro frontend). Needs "NDK" + "CMake" in the SDK Manager.
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("D:/EOGNew.jks")
            storePassword = "naruto162"
            keyAlias = "zgamesapp"
            keyPassword = "naruto162"
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            isMinifyEnabled = false
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            manifestPlaceholders["admobAppId"] = "ca-app-pub-5936725114299330~8942663706"
            // Validate minified release on devices before shipping.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    packaging {
        // MUST be true: the core .so is dlopen()ed by file path from nativeLibraryDir.
        jniLibs { useLegacyPackaging = true }
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
        resources { excludes += "**/*.knm" }
        resources { excludes += "commonMain/**" }
        resources { excludes += "nativeMain/**" }
        resources { excludes += "nonJvmMain/**" }
        resources { excludes += "**/default/manifest" }
        resources { excludes += "**/linkdata/**" }
        resources { excludes += "META-INF/kotlin-project-structure-metadata.json" }
        resources { excludes += "META-INF/**/LICENSE.txt" }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api",
            "-opt-in=androidx.compose.foundation.ExperimentalFoundationApi",
            "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi"
        )
    }
}

// ---- PS1 emulator core ---------------------------------------------------------------
// Downloads the SwanStation libretro core (arm64) from the libretro buildbot the first time you
// build, and bundles it as jniLibs/arm64-v8a/libswanstation_libretro_android.so. Offline or file
// missing? The build continues with a warning; put the file there by hand (see CORE_README.md).
// NOTE: SwanStation is GPL-3.0 - read the licensing note in CORE_README.md before publishing.
val psxCoreFile = file("src/main/jniLibs/arm64-v8a/libswanstation_libretro_android.so")
val downloadPsxCore by tasks.registering {
    onlyIf { !psxCoreFile.exists() }
    doLast {
        try {
            psxCoreFile.parentFile.mkdirs()
            val url = URL("https://buildbot.libretro.com/nightly/android/latest/arm64-v8a/swanstation_libretro_android.so.zip")
            val tmp = File(temporaryDir, "swanstation.zip")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 20000; conn.readTimeout = 120000
            conn.inputStream.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
            ZipInputStream(tmp.inputStream()).use { zin ->
                var e = zin.nextEntry
                while (e != null) {
                    if (e.name.endsWith(".so")) { psxCoreFile.outputStream().use { zin.copyTo(it) }; break }
                    e = zin.nextEntry
                }
            }
            logger.lifecycle("PS1 core downloaded: ${psxCoreFile.length() / 1024} KB")
        } catch (t: Throwable) {
            logger.warn("WARNING: could not download the PS1 core (${t.message}). Games will not run in-app until " +
                "libswanstation_libretro_android.so is placed in app/src/main/jniLibs/arm64-v8a/ (see CORE_README.md).")
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadPsxCore) }

dependencies {
    // Compose (BOM 2026.05.00: newest stack that still builds under AGP 8.13.
    // ui/ui-graphics pinned to 1.12.0: EmuCoreX calls the 1.12.x graphicsLayer
    // overload, which the BOM's 1.11.1 lacks -> NoSuchMethodError otherwise.)
    implementation(platform("androidx.compose:compose-bom:2026.05.00"))
    implementation("androidx.compose.ui:ui:1.12.0")
    implementation("androidx.compose.ui:ui-graphics:1.12.0")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Core
    implementation("androidx.core:core-ktx:1.16.0")
    // Views Material + splashscreen: the EmuCoreX AAR's merged resources
    // reference Theme.Material3.Dark.NoActionBar and Theme.SplashScreen.
    // Without these two, processDebugResources fails to link.
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Lifecycle + Navigation (EmuCoreX needs lifecycle 2.11 / navigation 2.10.2)
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.11.0")
    implementation("androidx.navigation:navigation-compose:2.10.2")

    // Networking / parsing / images
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    // Coil images use the same OkHttp client as feeds (Accept-Encoding
    // strip), so site images load past the zgamesandroid firewall.
    // EmuCoreX's own image stack (coil3). JetBrains Compose artifacts are
    // excluded: their AAR metadata requires AGP 9.1+, this project is AGP 8.13.
    implementation("io.coil-kt.coil3:coil-compose:3.6.3") {
        exclude(group = "org.jetbrains.compose.ui")
        exclude(group = "org.jetbrains.compose.foundation")
        exclude(group = "org.jetbrains.compose.runtime")
        exclude(group = "org.jetbrains.compose.animation")
        exclude(group = "org.jetbrains.compose.material3")
    }
    implementation("io.coil-kt.coil3:coil-network-okhttp:3.6.3")

    // Ads (test units wired in code; release needs a real AdMob app + units)
    implementation("com.google.android.gms:play-services-ads:25.4.0")

    // Billing (monthly Ad-Free subscription)
    implementation("com.android.billingclient:billing-ktx:8.0.0")

    // Firebase Remote Config: controls every ad unit id, interstitial
    // timer, banner/native switch and frequency (see ads/AdConfig.kt).
    // Needs app/google-services.json from your Firebase project; without
    // that file the app still builds and runs on the built-in defaults.
    implementation(platform("com.google.firebase:firebase-bom:34.0.0"))
    implementation("com.google.firebase:firebase-config")
    // EmuCoreX (PS2) runtime needs. The AAR is consumed through files(), which
    // carries NO transitive dependencies, so every library its classes call
    // must be declared here. Missing ones crash the PS2 screen with
    // NoClassDefFoundError (this was the "PS2 game crashes on Play" bug:
    // EmulationViewModel -> PlayerProfileRepository -> FirebaseAuth).
    implementation("com.google.firebase:firebase-auth")
    implementation("com.google.firebase:firebase-firestore")
    implementation("com.google.firebase:firebase-analytics")
    implementation("androidx.datastore:datastore-preferences:1.1.7")
    implementation("androidx.work:work-runtime-ktx:2.10.2")
    implementation("androidx.room:room-runtime:2.7.2")
    implementation("androidx.room:room-ktx:2.7.2")
    implementation("com.google.android.play:review-ktx:2.0.2")

    // Coroutines / serialization / protobuf-lite (PPSSPP's lite TombstoneProtos)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.google.protobuf:protobuf-javalite:3.25.5")

    // Archives for the mod installer
    implementation("com.github.junrar:junrar:8.1.2")
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.tukaani:xz:1.10")
    // ZIP decryption (ZipCrypto + AES); java.util.zip cannot decrypt.
    implementation(files("libs/zip4j-2.11.5.jar"))

    // Embedded PS2 core: EmuCoreX AAR (built from the EmuCoreX library
    // source). Consumed via files(), which carries no transitive deps, so
    // EmuCoreX's runtime deps are declared directly below.
    implementation("androidx.credentials:credentials:1.6.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.6.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.2.1")
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation("io.github.webrtc-sdk:android:150.7871.04")
    implementation("com.pierfrancescosoffritti.androidyoutubeplayer:core:13.0.0")
}

// Compose 1.12 / Navigation 2.10 / Lifecycle 2.11 declare "requires AGP 9.1+"
// in their AAR metadata. The code itself builds fine on AGP 8.13, so the
// metadata guard is switched off instead of forcing a toolchain upgrade.
tasks.configureEach {
    if (name.startsWith("check") && name.endsWith("AarMetadata")) {
        enabled = false
    }
}

// Apply the Google services plugin ONLY when google-services.json exists,
// so the project still builds before Firebase is set up.
if (file("google-services.json").exists()) {
    apply(plugin = "com.google.gms.google-services")
}
