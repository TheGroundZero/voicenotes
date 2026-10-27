// SPDX-License-Identifier: GPL-3.0-or-later
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "be.sequr.voicenotes"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "be.sequr.voicenotes"
        minSdk = 34
        targetSdk = 36
        versionCode = 7
        versionName = "0.5.1"

        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            // c++_shared: the backend libraries are separate shared libraries that must share one C++ runtime.
            cmake { arguments += listOf("-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON", "-DANDROID_STL=c++_shared") }
        }
    }

    // Release signing: a keystore.properties file (storeFile, storePassword, keyAlias, keyPassword; never committed, see
    // docs/RELEASING.md) or, in CI, the same file written from secrets. Without it the release APK is left unsigned,
    // which is what F-Droid wants (it signs its own builds).
    val keystoreProperties = rootProject.file("keystore.properties")
    signingConfigs {
        if (keystoreProperties.exists()) {
            create("release") {
                val props = Properties().apply { keystoreProperties.inputStream().use { load(it) } }
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    // The Android Gradle Plugin otherwise adds a list of the app's dependencies to the APK, encrypted with a Google
    // public key (and with fresh randomness, so no two builds are identical). Nobody but Google can read it, which
    // F-Droid does not accept and which makes reproducible builds impossible.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            vcsInfo.include = false // the git commit would otherwise be stamped into the APK, which differs between clones
            if (keystoreProperties.exists()) signingConfig = signingConfigs.getByName("release")
        }
        // The release build (code and resource shrinking on) installed next to the real app under its own ID and signed
        // with the debug key, to try a release on a phone without replacing the real app or its data.
        create("releaseCheck") {
            initWith(getByName("release"))
            applicationIdSuffix = ".releasecheck"
            signingConfig = signingConfigs.getByName("debug")
            isDebuggable = true // only so adb can copy a model into it; shrinking and optimisation are unchanged
            matchingFallbacks += "release"
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
    }

    packaging {
        // ggml loads its processor-specific backend libraries by file path, so they must be extracted at install time.
        jniLibs { useLegacyPackaging = true }
    }

    testOptions {
        // Unit tests run on the JVM against the Android stubs; framework calls they never reach need no real behaviour.
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
        baseline = file("lint-baseline.xml")
        checkDependencies = false
    }
}

// Exported so M2 can write proper Room migrations.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

// Privacy guard (docs/ROADMAP.md, "Design rules"): network access exists only for the model downloader.
// 1. The manifest may request INTERNET and ACCESS_NETWORK_STATE, but no other network-related permission.
// 2. Only ModelDownloader.kt may use network APIs.
tasks.register("verifyNetworkIsolation") {
    val manifest = file("src/main/AndroidManifest.xml")
    val sources = fileTree("src/main/kotlin") { include("**/*.kt") }
    doLast {
        // BLUETOOTH_CONNECT is only used to pick a connected Bluetooth microphone (see BluetoothMic.kt); no other
        // Bluetooth permission (scan, advertise, admin) is acceptable.
        val allowed = setOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE", "android.permission.BLUETOOTH_CONNECT")
        val networkish = Regex("""android\.permission\.(INTERNET|ACCESS_\w*(NETWORK|WIFI)\w*|CHANGE_\w*(NETWORK|WIFI)\w*|NEARBY_WIFI_DEVICES|BLUETOOTH\w*)""")
        val requested = networkish.findAll(manifest.readText()).map { it.value }.toSet()
        check(allowed.containsAll(requested)) { "Unexpected network-related permissions: ${requested - allowed}" }
        val banned = listOf("java.net.", "javax.net.", "okhttp", "HttpURLConnection", "android.net.http", "retrofit", "io.ktor")
        sources.filter { it.name != "ModelDownloader.kt" }.forEach { f ->
            val text = f.readText()
            banned.firstOrNull { it in text }?.let { error("${f.name} uses '$it'; network code is only allowed in ModelDownloader.kt") }
        }
    }
}
tasks.named("preBuild") { dependsOn("verifyNetworkIsolation") }

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")

    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    testImplementation("junit:junit:4.13.2")
    // Android's own org.json is only a stub in local unit tests; backups use it, so the tests need the real one.
    testImplementation("org.json:json:20240303")
}

// Privacy guard 3: no analytics, crash-reporting, advertising or Google-services libraries anywhere in the app's
// runtime dependencies (the F-Droid policy forbids them and the promise to users excludes them).
tasks.register("verifyNoTrackers") {
    val banned = listOf(
        "com.google.android.gms", "com.google.firebase", "com.google.android.play", "com.google.mlkit",
        "com.google.android.datatransport", "com.crashlytics", "io.sentry", "com.bugsnag", "com.flurry",
        "com.facebook", "com.adjust", "com.appsflyer", "io.branch", "com.mixpanel", "com.amplitude",
        "com.segment", "org.matomo", "com.onesignal", "com.applovin", "com.unity3d.ads", "com.ironsource",
    )
    val runtime = configurations.named("releaseRuntimeClasspath")
    doLast {
        val found = runtime.get().incoming.resolutionResult.allComponents
            .mapNotNull { it.moduleVersion }
            .filter { m -> banned.any { m.group == it || m.group.startsWith("$it.") } }
            .map { "${it.group}:${it.name}:${it.version}" }
        check(found.isEmpty()) { "Forbidden analytics/ads/Google-services dependencies: $found" }
    }
}
