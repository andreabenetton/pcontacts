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
            // R8 + proguard-rules.pro. Minification on so the BouncyCastle /
            // kotlinx-serialization / Retrofit / Room reflection-keep rules
            // get exercised by `:app:assembleRelease` in CI.
            isMinifyEnabled = true
            isShrinkResources = false   // resource shrinking off until we audit
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

    testOptions {
        unitTests.isIncludeAndroidResources = true
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

    // ProtonSyncAdapter.onPerformSync is blocking but EmailSyncEngine.sync is
    // suspend; runBlocking{} bridges the two on the SyncAdapter's worker thread.
    implementation(libs.bundles.kotlinx.coroutines)

    // WorkManager — periodic sync belt-and-suspenders for vendor power profiles
    // where the SyncAdapter scheduling is unreliable (plan §3.5).
    implementation(libs.androidx.work.runtime.ktx)

    // ADR-0015: enforce no direct Log / println / System.out.* calls.
    lintChecks(project(":tools:lint"))

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.kotlinx.coroutines.test)
}

// ADR-0015: license-compatibility enforcement. Walks :app's resolved release
// runtime classpath, fetches POM-declared licenses via ArtifactResolutionQuery,
// and fails if any artifact carries a license not on the allowlist in
// config/allowed-licenses.json.
tasks.register("checkLicense") {
    group = "verification"
    description = "Fails the build if any dependency in :app's release classpath " +
        "carries a license not on the ADR-0015 allowlist."
    notCompatibleWithConfigurationCache("walks resolved configurations at execution time")

    doLast {
        val allowedFile = rootProject.file("config/allowed-licenses.json")
        require(allowedFile.exists()) { "Missing license allowlist: $allowedFile" }

        val allowedNames = mutableSetOf<String>()
        val excludedModules = mutableSetOf<String>()
        val jsonText = allowedFile.readText()
        val licRegex = Regex(""""moduleLicense"\s*:\s*"([^"]+)"""")
        licRegex.findAll(jsonText).forEach { allowedNames += it.groupValues[1].lowercase() }
        val exclRegex = Regex(""""excludeModules"\s*:\s*\[([^\]]*)]""", RegexOption.DOT_MATCHES_ALL)
        exclRegex.find(jsonText)?.let { match ->
            Regex(""""([^"]+)"""").findAll(match.groupValues[1]).forEach {
                excludedModules += it.groupValues[1]
            }
        }

        val config = configurations.findByName("releaseRuntimeClasspath")
            ?: throw GradleException("Configuration 'releaseRuntimeClasspath' not found")

        val componentIds = config.incoming.resolutionResult.allDependencies
            .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
            .map { it.selected.id }
            .filterIsInstance<org.gradle.api.artifacts.component.ModuleComponentIdentifier>()
            .filter { "${it.group}:${it.module}" !in excludedModules }
            .toSet()

        if (componentIds.isEmpty()) {
            logger.lifecycle("ADR-0015 license check: no external dependencies found.")
            return@doLast
        }

        val result = dependencies.createArtifactResolutionQuery()
            .forComponents(componentIds)
            .withArtifacts(
                org.gradle.maven.MavenModule::class.java,
                org.gradle.maven.MavenPomArtifact::class.java
            )
            .execute()

        val violations = mutableListOf<String>()
        val licensePattern = Regex("""<license>\s*<name>\s*([^<]+?)\s*</name>""", RegexOption.DOT_MATCHES_ALL)

        for (component in result.resolvedComponents) {
            val pomArtifacts = component.getArtifacts(org.gradle.maven.MavenPomArtifact::class.java)
            for (artifact in pomArtifacts) {
                if (artifact !is org.gradle.api.artifacts.result.ResolvedArtifactResult) continue
                val pomText = artifact.file.readText()
                val licenses = licensePattern.findAll(pomText).map { it.groupValues[1].trim() }.toList()
                if (licenses.isEmpty()) {
                    violations += "${component.id} — no license declared in POM"
                } else {
                    val unrecognized = licenses.filter { it.lowercase() !in allowedNames }
                    for (lic in unrecognized) {
                        violations += "${component.id} — disallowed license: $lic"
                    }
                }
            }
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "ADR-0015 — license violations detected:\n  - " +
                    violations.distinct().sorted().joinToString("\n  - ")
            )
        }
        logger.lifecycle("ADR-0015 license check passed — ${componentIds.size} dependencies scanned.")
    }
}

afterEvaluate {
    tasks.matching { it.name == "assembleRelease" }.configureEach {
        dependsOn(":core:proton-api:verifyCertificatePins")
    }
}
