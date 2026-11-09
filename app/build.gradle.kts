import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

// Release signing lives outside the repo (see README). Local override: keystore.properties at the root.
val signingProps = Properties().apply {
    val candidates = listOf(
        rootProject.file("keystore.properties"),
        File(System.getProperty("user.home"), "keystores/sound-keystore.properties"),
    )
    candidates.firstOrNull { it.exists() }?.inputStream()?.use { load(it) }
}

kotlin {
    jvmToolchain(21)
}

android {
    namespace = "com.villamediana.sound"
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "com.villamediana.sound"
        minSdk = 26
        targetSdk = 36
        versionCode = 61
        versionName = "1.14.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The FreeSong relay (relay/): profiles, friends, shared songs and jams.
        buildConfigField("String", "RELAY_URL", "\"https://freesong.simplyonegoal.com\"")
    }

    signingConfigs {
        if (signingProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            // No R8: it rewrites NewPipe/OkHttp/Kotlin internals and the search broke on the phone
            // while every JVM live test passed. The release runs the same bytecode the tests run.
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Never fall back to the debug key: an APK signed with another key cannot update the installed app.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        localeFilters += listOf("pt", "es", "en")
        // The model is memory-mapped straight from the APK.
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/README.md",
                "META-INF/CHANGES",
                "META-INF/COPYRIGHT",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
            )
        }
    }

    // Room's exported schemas: the migration tests (Robolectric reads the debug variant's assets)
    // open them from there. Debug builds only; the release APK doesn't carry them.
    sourceSets.getByName("debug").assets.directories.add("$projectDir/schemas")

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
            all {
                it.systemProperty("sound.live", System.getenv("SOUND_LIVE") ?: "")
                // Chords checked on real songs (tools/chords): where the audio and Basic Pitch frames are.
                it.systemProperty("sound.chords.dir", System.getenv("SOUND_CHORDS_DIR") ?: "")
                it.systemProperty("sound.chords.variant", System.getenv("SOUND_CHORDS_VARIANT") ?: "")
                it.systemProperty("sound.bench.dir", System.getenv("SOUND_BENCH_DIR") ?: "")
                it.systemProperty("sound.cifra.dir", System.getenv("SOUND_CIFRA_DIR") ?: "")
                // Robolectric 4.17 on JDK 21 at API 36 needs these to touch FileDescriptor internals.
                it.jvmArgs(
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                )
                it.maxHeapSize = "3g"
                it.testLogging { events("failed", "skipped"); showStandardStreams = true }
            }
        }
    }
}

// Fail fast when a release is requested without the keystore (~/keystores/sound-keystore.properties).
gradle.taskGraph.whenReady {
    val wantsRelease = allTasks.any { it.project == project && it.name.contains("Release") && (it.name.startsWith("assemble") || it.name.startsWith("package") || it.name.startsWith("bundle")) }
    if (wantsRelease && signingProps.getProperty("storeFile") == null) {
        throw GradleException("Release signing keystore not found: expected ~/keystores/sound-keystore.properties or ./keystore.properties")
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    coreLibraryDesugaring(libs.android.desugar)

    implementation(libs.androidx.core)
    implementation(libs.androidx.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.foundation)
    implementation(libs.compose.animation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    implementation(libs.media3.common.ktx)
    implementation(libs.media3.ui.compose)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime)
    implementation(libs.datastore.preferences)
    implementation(libs.palette)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.okhttp)
    implementation(libs.coroutines.android)
    implementation(libs.serialization.json)
    implementation(libs.newpipe.extractor)
    // LiteRT (TensorFlow Lite) for Basic Pitch, the 200 KB model that hears the notes of a song (chords).

    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.room.testing)
    testImplementation(libs.work.testing)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
}
