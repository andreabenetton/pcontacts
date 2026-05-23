// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "io.pcontacts.app"
    compileSdk = libs.versions.android.compile.sdk.get().toInt()

    defaultConfig {
        applicationId = "io.pcontacts.app"
        minSdk = libs.versions.android.min.sdk.get().toInt()
        targetSdk = libs.versions.android.target.sdk.get().toInt()
        versionCode = 1
        versionName = "0.0.1"
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            isDebuggable = false
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

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // BouncyCastle (bcpg + bcprov + bcutil) each ship an identical
            // META-INF/versions/9/OSGI-INF/MANIFEST.MF; the APK packager
            // refuses to pick one without an explicit rule.
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/{AL2.0,LGPL2.1}",
                "META-INF/INDEX.LIST",
                "META-INF/io.netty.versions.properties"
            )
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)         // kept for Theme.AppCompat parent on Activity manifest theme
    implementation(libs.androidx.activity)
    implementation(libs.androidx.activity.compose)

    // Compose + Material3 (matches the ProtonVPN/android-app stack).
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.runtime.compose)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // :app pulls in the orchestration + feature modules. Per ADR-0011 it does
    // NOT depend on :core:crypto or :core:proton-api directly — those are
    // reachable transitively through :core:sync.
    implementation(project(":core:sync"))
    api(project(":core:logging"))                // 'api' so :app classes (incl. ProtonSyncAdapter, AndroidLogcatSink) can name Logger types directly
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:settings"))

    // ADR-0015: enforce no direct Log / println / System.out.* calls.
    lintChecks(project(":tools:lint"))
}
