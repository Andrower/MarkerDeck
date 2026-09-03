plugins {
    id("com.android.application")
}

android {
    namespace = "com.andrower.markerdeck"
    compileSdk = 36
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "com.andrower.markerdeck"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.4.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            // The browser source remains the single Android asset source of truth.
            assets.srcDir(rootProject.file("../src/web"))
        }
    }

    lint {
        // AGP 9.0.1 and Gradle 9.1.0 are the validated A01 pair; upgrades are reviewed separately.
        disable += "AndroidGradlePluginVersion"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.nanohttpd:nanohttpd:2.3.1")
    implementation("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
    // JVM tests must use the real implementation instead of android.jar stubs.
    testImplementation("org.json:json:20240303")
}
