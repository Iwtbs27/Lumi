import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    id("com.chaquo.python")
}

val standardSigningProperties = Properties().apply {
    val propertiesFile = rootProject.file("signing/standard.properties")
    if (propertiesFile.isFile) propertiesFile.inputStream().use { load(it) }
}

android {
    namespace = "ai.opencyvis"
    compileSdk = 36

    // Keep standard APK updates on the same key across machines. Missing local
    // signing files fail validation instead of generating another debug key.
    signingConfigs {
        create("standardLocal") {
            storeFile = rootProject.file("signing/standard.keystore")
            storePassword = standardSigningProperties.getProperty("storePassword")
            keyAlias = standardSigningProperties.getProperty("keyAlias")
            keyPassword = standardSigningProperties.getProperty("keyPassword")
        }
    }

    // Platform signing: only enable if platform-key/platform.jks exists.
    // Without it, builds use the default debug keystore.
    val platformKeyFile = file("../platform-key/platform.jks")
    if (platformKeyFile.exists()) {
        signingConfigs {
            create("platform") {
                storeFile = platformKeyFile
                storePassword = System.getenv("PLATFORM_STORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("PLATFORM_KEY_ALIAS") ?: "platform"
                keyPassword = System.getenv("PLATFORM_KEY_PASSWORD") ?: "android"
            }
        }
    }

    defaultConfig {
        applicationId = "ai.opencyvis"
        minSdk = 30  // Android 11+ for SurfaceControl.screenshot()
        targetSdk = 36
        versionCode = 241
        versionName = "2.4.1-memory-ui"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // CPython 3.11 preserves the application's existing 32-bit and 64-bit coverage.
            abiFilters += listOf("armeabi-v7a", "arm64-v8a", "x86", "x86_64")
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=none"
            }
        }
    }

    lint {
        // System app uses hidden APIs and runs on a known SDK — NewApi is a false positive.
        abortOnError = false
    }

    flavorDimensions += "privilege"
    productFlavors {
        create("system") {
            dimension = "privilege"
        }
        create("standard") {
            dimension = "privilege"
            applicationIdSuffix = ".standard"
        }
    }

    buildTypes {
        debug {
            if (signingConfigs.findByName("platform") != null) {
                signingConfig = signingConfigs.getByName("platform")
            }
        }
        release {
            isMinifyEnabled = false
            if (signingConfigs.findByName("platform") != null) {
                signingConfig = signingConfigs.getByName("platform")
            }
        }
    }

    // Standard flavor uses its persistent local key for both build types.
    androidComponents {
        onVariants { variant ->
            if (variant.flavorName == "standard") {
                variant.signingConfig.setConfig(signingConfigs.getByName("standardLocal"))
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
        unitTests.isIncludeAndroidResources = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/jni/CMakeLists.txt")
        }
    }

    buildFeatures {
        viewBinding = true
        aidl = true
        prefab = true
    }

    packaging {
        resources {
            // Bouncy Castle OSGI manifests conflict
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
        jniLibs {
            excludes += listOf("**/libonnxruntime.so", "**/libsherpa-onnx-jni.so")
        }
    }
}

chaquopy {
    defaultConfig {
        version = "3.11"
        providers.gradleProperty("chaquopy.buildPython").orNull?.let { buildPython(it) }
        pip { install("openpyxl==3.1.5") }
    }
}

tasks.matching { it.name == "preStandardDebugBuild" || it.name == "preStandardReleaseBuild" }
    .configureEach {
        doFirst {
            check(listOf("storePassword", "keyAlias", "keyPassword").all {
                    !standardSigningProperties.getProperty(it).isNullOrBlank()
                }) {
                "Restore standard.properties from the signing backup into android/signing/."
            }
        }
    }

// Local JVM tests use the desktop Conscrypt natives supplied with Robolectric.
// Keep the Android artifact on the app and device-test classpaths.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    exclude(group = "org.conscrypt", module = "conscrypt-android")
}

dependencies {
    // Hidden APIs accessed via reflection at runtime — no compile-time stubs needed

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.0")
    implementation("androidx.preference:preference-ktx:1.2.1")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // On-device streaming ASR. Sherpa 8.5.1's JNI library is linked against
    // ONNX Runtime 1.24.3; exclude the stale transitive lib-onnx package.
    implementation("com.bihe0832.android:lib-sherpa-onnx:8.5.1") {
        exclude(group = "com.bihe0832.android", module = "lib-onnx")
    }
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")

    // Lifecycle (process-wide foreground/background detection for OverlayService)
    implementation("androidx.lifecycle:lifecycle-process:2.8.7")
    implementation("androidx.lifecycle:lifecycle-common-java8:2.8.7")

    // OkHttp for LLM API calls
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // QR code generation (for Feishu bot registration)
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")

    // Production uses Android's org.json. The desktop library is test-only:
    // its extra overloads compile successfully but do not exist on devices.

    // Room
    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // Shizuku SDK (user-space shell service via bindUserService)
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // ADB pairing native (BoringSSL for SPAKE2 + HKDF + AES-GCM)
    implementation("io.github.vvb2060.ndk:boringssl:20250114")
    implementation("org.lsposed.libcxx:libcxx:27.0.12077973")
    // ADB key management (X.509 cert generation) + TLS with exportKeyingMaterial
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80")
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    // Testing
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.mockito:mockito-core:5.14.0")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("io.mockk:mockk:1.13.12")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testRuntimeOnly("org.conscrypt:conscrypt-openjdk-uber:2.5.2")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("app.cash.turbine:turbine:1.2.0")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
