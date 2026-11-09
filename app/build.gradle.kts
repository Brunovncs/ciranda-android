import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

// The version lives in one place, the Rust crate's manifest: a release bumps it there only.
val crateVersion: String = rootProject.file("rust/Cargo.toml").readLines()
    .first { it.startsWith("version = ") }
    .substringAfter('"').substringBefore('"')
val versionParts = crateVersion.split('.').map { it.toInt() }

// Which processors the native library is built for: phones by default; add x86_64 for the
// emulator (`-Pabis=arm64-v8a,x86_64`).
val abis: List<String> = (findProperty("abis") as String? ?: "arm64-v8a").split(',').map { it.trim() }
val releaseBuild = gradle.startParameter.taskNames.any { it.contains("Release", ignoreCase = true) }

// The release key: from the environment in CI, from keystore.properties here. Without one a
// release build is signed with the debug key, which updates cannot cross.
val keystore = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun secret(name: String, env: String): String? = System.getenv(env) ?: keystore.getProperty(name)

val sdkDir: String = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: Properties().run {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    getProperty("sdk.dir") ?: throw GradleException("Set ANDROID_HOME or sdk.dir in local.properties")
}

android {
    namespace = "app.ciranda.android"
    compileSdk = 37
    ndkVersion = "30.0.16248370"

    defaultConfig {
        applicationId = "app.ciranda.android"
        minSdk = 26
        targetSdk = 36
        versionCode = versionParts[0] * 10000 + versionParts[1] * 100 + versionParts[2]
        versionName = crateVersion
        ndk { abiFilters += abis }
    }

    signingConfigs {
        create("release") {
            val store = secret("storeFile", "CIRANDA_KEYSTORE")
            if (store != null) {
                storeFile = rootProject.file(store)
                storePassword = secret("storePassword", "CIRANDA_KEYSTORE_PASSWORD")
                keyAlias = secret("keyAlias", "CIRANDA_KEY_ALIAS")
                keyPassword = secret("keyPassword", "CIRANDA_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (signingConfigs.getByName("release").storeFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // Compressed in the APK and unpacked on install: the download is what friends wait for,
        // and the native library is most of it.
        jniLibs { useLegacyPackaging = true }
    }
}

// The Rust half: the desktop client's core and media, built for Android into jniLibs, with the
// Java half of LiveKit's WebRTC that it calls into.
val cargoBuild = tasks.register<Exec>("cargoBuild") {
    val rustDir = rootProject.file("rust")
    workingDir = rustDir
    val args = mutableListOf("cargo", "ndk")
    abis.forEach { args += listOf("-t", it) }
    args += listOf("-P", "26", "-o", project.file("src/main/jniLibs").absolutePath, "build")
    if (releaseBuild) args += "--release"
    commandLine(args)
    environment("ANDROID_NDK_HOME", File(sdkDir, "ndk/30.0.16248370").absolutePath)
    doLast {
        val jar = fileTree(rustDir.resolve("target")) { include("**/livekit_webrtc/**/libwebrtc.jar") }.files.firstOrNull()
            ?: throw GradleException("libwebrtc.jar not found under rust/target")
        jar.copyTo(project.file("libs/libwebrtc.jar"), overwrite = true)
    }
}

tasks.named("preBuild") { dependsOn(cargoBuild) }

dependencies {
    implementation(files("libs/libwebrtc.jar"))
    // Must match the rustls-platform-verifier-android crate in rust/Cargo.lock.
    implementation("org.rustls:rustls-platform-verifier:0.2.0")

    val bom = platform("androidx.compose:compose-bom:2026.09.00")
    implementation(bom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended:1.7.8")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.19.1")
    // The app lock (`AppLock`): the phone's own prompt, which needs a FragmentActivity.
    implementation("androidx.biometric:biometric:1.1.0")
    implementation("androidx.fragment:fragment:1.9.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
