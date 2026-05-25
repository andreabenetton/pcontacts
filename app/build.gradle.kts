// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.dependency.check)
}

android {
    namespace = "io.pcontacts.app"
    compileSdk = libs.versions.android.compile.sdk.get().toInt()

    defaultConfig {
        applicationId = "io.pcontacts.app"
        minSdk = libs.versions.android.min.sdk.get().toInt()
        targetSdk = libs.versions.android.target.sdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0"
    }

    val releaseStoreFile = project.findProperty("RELEASE_STORE_FILE") as String?
        ?: System.getenv("RELEASE_STORE_FILE")
    if (releaseStoreFile != null) {
        signingConfigs {
            create("release") {
                storeFile = file(releaseStoreFile)
                storePassword = (project.findProperty("RELEASE_STORE_PASSWORD") as String?)
                    ?: System.getenv("RELEASE_STORE_PASSWORD") ?: ""
                keyAlias = (project.findProperty("RELEASE_KEY_ALIAS") as String?)
                    ?: System.getenv("RELEASE_KEY_ALIAS") ?: ""
                keyPassword = (project.findProperty("RELEASE_KEY_PASSWORD") as String?)
                    ?: System.getenv("RELEASE_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfig = signingConfigs.findByName("release")
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

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
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
    implementation(libs.androidx.lifecycle.viewmodel)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // :app pulls in the orchestration + feature modules. Per ADR-0011 it does
    // NOT depend on :core:crypto or :core:proton-api directly — those are
    // reachable transitively through :core:sync.
    implementation(project(":core:sync"))
    implementation(project(":core:storage"))      // UserPreferences for sync interval setting
    api(project(":core:logging"))                // 'api' so :app classes (incl. ProtonSyncAdapter, AndroidLogcatSink) can name Logger types directly
    implementation(project(":feature:onboarding"))
    implementation(project(":feature:settings"))

    // ProtonSyncAdapter.onPerformSync is blocking but EmailSyncEngine.sync is
    // suspend; runBlocking{} bridges the two on the SyncAdapter's worker thread.
    implementation(libs.bundles.kotlinx.coroutines)

    // WorkManager — periodic sync belt-and-suspenders for vendor power profiles
    // where the SyncAdapter scheduling is unreliable (plan §3.5).
    implementation(libs.androidx.work.runtime.ktx)

    // Chrome Custom Tabs — human-verification captcha flow (9001 recovery).
    implementation(libs.androidx.browser)

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

// ADR-0009: manifest-invariant enforcement. Reads the merged manifests after
// processDebugManifest / processReleaseManifest and asserts the security-
// critical attributes haven't been overridden by a library's manifest merger.
// Also validates data_extraction_rules.xml exclusion completeness.
tasks.register("verifyManifestInvariants") {
    group = "verification"
    description = "Fails the build if merged manifests violate ADR-0009 " +
        "security invariants (allowBackup, debuggable, dataExtractionRules)."
    notCompatibleWithConfigurationCache("reads merged manifest files at execution time")

    dependsOn("processDebugManifest", "processReleaseManifest")

    doLast {
        val violations = mutableListOf<String>()

        fun parseApplicationAttrs(file: java.io.File): Map<String, String?> {
            val text = file.readText()
            val appBlock = Regex("""<application\b[^>]*>""", RegexOption.DOT_MATCHES_ALL)
                .find(text)?.value ?: error("No <application> tag found in ${file.path}")
            fun attr(name: String): String? =
                Regex("""android:$name\s*=\s*"([^"]*)"""").find(appBlock)?.groupValues?.get(1)
            return mapOf(
                "allowBackup" to attr("allowBackup"),
                "debuggable" to attr("debuggable"),
                "dataExtractionRules" to attr("dataExtractionRules")
            )
        }

        // --- Debug manifest ---
        val debugManifest = file(
            "build/intermediates/merged_manifests/debug/processDebugManifest/AndroidManifest.xml"
        )
        if (debugManifest.exists()) {
            val attrs = parseApplicationAttrs(debugManifest)
            if (attrs["allowBackup"] != "false") {
                violations += "debug: android:allowBackup must be \"false\", got \"${attrs["allowBackup"]}\""
            }
            if (attrs["dataExtractionRules"] != "@xml/data_extraction_rules") {
                violations += "debug: android:dataExtractionRules must be " +
                    "\"@xml/data_extraction_rules\", got \"${attrs["dataExtractionRules"]}\""
            }
        } else {
            violations += "debug: merged manifest not found at ${debugManifest.path}"
        }

        // --- Release manifest ---
        val releaseManifest = file(
            "build/intermediates/merged_manifests/release/processReleaseManifest/AndroidManifest.xml"
        )
        if (releaseManifest.exists()) {
            val attrs = parseApplicationAttrs(releaseManifest)
            if (attrs["allowBackup"] != "false") {
                violations += "release: android:allowBackup must be \"false\", got \"${attrs["allowBackup"]}\""
            }
            if (attrs["dataExtractionRules"] != "@xml/data_extraction_rules") {
                violations += "release: android:dataExtractionRules must be " +
                    "\"@xml/data_extraction_rules\", got \"${attrs["dataExtractionRules"]}\""
            }
            if (attrs["debuggable"] != null && attrs["debuggable"] != "false") {
                violations += "release: android:debuggable must be absent or \"false\", " +
                    "got \"${attrs["debuggable"]}\""
            }
        } else {
            violations += "release: merged manifest not found at ${releaseManifest.path}"
        }

        // --- data_extraction_rules.xml completeness ---
        val rulesFile = file("src/main/res/xml/data_extraction_rules.xml")
        if (rulesFile.exists()) {
            val rulesText = rulesFile.readText()
            val requiredDomains = listOf("root", "file", "database", "sharedpref", "external")
            for (section in listOf("cloud-backup", "device-transfer")) {
                val sectionBlock = Regex(
                    """<$section>(.*?)</$section>""",
                    RegexOption.DOT_MATCHES_ALL
                ).find(rulesText)?.groupValues?.get(1)
                if (sectionBlock == null) {
                    violations += "data_extraction_rules.xml: missing <$section> section"
                } else {
                    for (domain in requiredDomains) {
                        if (!sectionBlock.contains("""domain="$domain"""")) {
                            violations += "data_extraction_rules.xml: <$section> missing " +
                                "exclude for domain=\"$domain\""
                        }
                    }
                }
            }
        } else {
            violations += "data_extraction_rules.xml not found at ${rulesFile.path}"
        }

        if (violations.isNotEmpty()) {
            throw GradleException(
                "ADR-0009 — manifest invariant violations:\n  - " +
                    violations.joinToString("\n  - ")
            )
        }
        logger.lifecycle(
            "ADR-0009 manifest invariant check passed — " +
                "debug manifest, release manifest, data_extraction_rules.xml all verified."
        )
    }
}

// OWASP Dependency-Check — scans resolved classpath for known CVEs.
// Runs weekly in CI and on PRs touching libs.versions.toml.
// NVD API key (free, https://nvd.nist.gov/developers/request-an-api-key)
// is required since dependency-check v9; set NVD_API_KEY in CI secrets.
dependencyCheck {
    failBuildOnCVSS = 7.0f
    suppressionFile = "$rootDir/config/dependency-check-suppressions.xml"
    formats = listOf("HTML", "JSON", "SARIF")
    skipConfigurations = listOf("lintClassPath", "lintChecks")
    nvd.apiKey = System.getenv("NVD_API_KEY") ?: ""
}

afterEvaluate {
    tasks.matching { it.name == "assembleRelease" }.configureEach {
        dependsOn(":core:proton-api:verifyCertificatePins")
        dependsOn("verifyManifestInvariants")
    }
}
