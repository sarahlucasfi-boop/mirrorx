plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Workaround: Google Drive for Desktop constantly re-syncs a `desktop.ini`
// file into every subfolder of the synced mirror, including the build
// intermediates. AGP rejects it during resource parsing. We delete the
// offending file from every build-output location at the start of every
// Gradle invocation. Cheap, idempotent, safe.
val cleanupDesktopIni = tasks.register("cleanupDesktopIni") {
    description = "Delete desktop.ini that Drive re-injects into build folders."
    doLast {
        listOf(
            "build/intermediates/packaged_res",
            "build/intermediates/merged_res",
            "build/intermediates/incremental",
            "build/intermediates/merged_not_compiled_res",
            "src/main/res",
        ).forEach { rel ->
            file(rel).walkTopDown()
                .filter { it.name.equals("desktop.ini", ignoreCase = true) }
                .forEach { it.delete() }
        }
    }
}

android {
    namespace = "com.mirrorx.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.mirrorx.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 36
        versionName = "1.9.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
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
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.5"
    }
}

dependencies {
    // Compose
    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.6.2")

    // WebSocket
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Debug
    debugImplementation("androidx.compose.ui:ui-tooling")
}

// Hook the cleanup before every buildable task
afterEvaluate {
    tasks.matching { it.name.startsWith("assemble") || it.name.startsWith("process") }
        .configureEach { dependsOn(cleanupDesktopIni) }
}
