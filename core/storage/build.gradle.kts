// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "io.pcontacts.core.storage"
    compileSdk = libs.versions.android.compile.sdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.min.sdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            java.srcDirs("src/test/kotlin")
            assets.srcDirs("schemas")
        }
    }

    // Robolectric needs the merged manifest + resources on the test classpath
    // so it can boot a fake Android runtime and serve Room a Context.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// Room: export schemas to disk so MigrationTestHelper can diff v(N) → v(N+1)
// once a migration exists. The first migration commit will add a JSON dump
// under :core:storage/schemas/.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // ADR-0009: SecretStore — EncryptedSharedPreferences + Keystore AEAD.
    implementation(libs.androidx.security.crypto)

    // ADR-0008: Room mapping store (ProtonID ↔ RawContactID, sync state).
    // 'api' because PcontactsDatabase extends RoomDatabase; downstream
    // consumers (e.g. :core:sync's SyncBootstrap) need that supertype
    // on their compile classpath even to *call* a DAO method.
    api(libs.bundles.room)
    ksp(libs.androidx.room.compiler)

    implementation(project(":core:logging"))

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.androidx.room.testing)

    lintChecks(project(":tools:lint"))
}
