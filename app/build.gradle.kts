// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Properties

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
        versionCode = 19
        versionName = "2.0.0"
        base.archivesName.set("pcontacts")
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

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            // BouncyCastle (bcpg + bcprov + bcutil) each ship an identical
            // META-INF/versions/9/OSGI-INF/MANIFEST.MF (and, since BC 1.85,
            // an identical META-INF/LICENSE.md); the APK packager refuses to
            // pick one without an explicit rule. The BC license text stays
            // available via the repo's NOTICE file.
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/LICENSE.md",
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

    // ADR-0015: enforce no direct Log / println / System.out.* calls.
    lintChecks(project(":tools:lint"))

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.kotlinx.coroutines.test)
}

/** Every external artifact on :app's resolved release runtime classpath. */
fun resolvedReleaseComponents(): Set<org.gradle.api.artifacts.component.ModuleComponentIdentifier> {
    val config = configurations.findByName("releaseRuntimeClasspath")
        ?: throw GradleException("Configuration 'releaseRuntimeClasspath' not found")
    return config.incoming.resolutionResult.allDependencies
        .filterIsInstance<org.gradle.api.artifacts.result.ResolvedDependencyResult>()
        .map { it.selected.id }
        .filterIsInstance<org.gradle.api.artifacts.component.ModuleComponentIdentifier>()
        .toSet()
}

/** POM-declared license names per "group:name:version", via ArtifactResolutionQuery. */
fun pomLicenses(
    components: Set<org.gradle.api.artifacts.component.ModuleComponentIdentifier>
): Map<String, List<String>> {
    if (components.isEmpty()) return emptyMap()
    val licensePattern = Regex("""<license>\s*<name>\s*([^<]+?)\s*</name>""", RegexOption.DOT_MATCHES_ALL)
    val result = dependencies.createArtifactResolutionQuery()
        .forComponents(components)
        .withArtifacts(
            org.gradle.maven.MavenModule::class.java,
            org.gradle.maven.MavenPomArtifact::class.java
        )
        .execute()
    val licenses = mutableMapOf<String, List<String>>()
    for (component in result.resolvedComponents) {
        for (artifact in component.getArtifacts(org.gradle.maven.MavenPomArtifact::class.java)) {
            if (artifact !is org.gradle.api.artifacts.result.ResolvedArtifactResult) continue
            val pomText = artifact.file.readText()
            licenses[component.id.displayName] = licensePattern.findAll(pomText).map { it.groupValues[1].trim() }.toList()
        }
    }
    return licenses
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

        val componentIds = resolvedReleaseComponents()
            .filter { "${it.group}:${it.module}" !in excludedModules }
            .toSet()

        if (componentIds.isEmpty()) {
            logger.lifecycle("ADR-0015 license check: no external dependencies found.")
            return@doLast
        }

        val violations = mutableListOf<String>()
        for ((id, licenses) in pomLicenses(componentIds)) {
            if (licenses.isEmpty()) {
                violations += "$id — no license declared in POM"
            } else {
                val unrecognized = licenses.filter { it.lowercase() !in allowedNames }
                for (lic in unrecognized) {
                    violations += "$id — disallowed license: $lic"
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

// ADR-0024: the dependency audit snapshot the app ships as an asset. Built from
// the resolved release classpath, the POM licenses, the Dependency-Check JSON
// report and the suppression file; committed so the F-Droid build reproduces it.
val dependencyAuditFile = layout.projectDirectory.file("src/main/assets/dependency-audit.json")
val dependencyCheckReport = layout.buildDirectory.file("reports/dependency-check/dependency-check-report.json")
val suppressionFileForAudit = rootProject.file("config/dependency-check-suppressions.xml")

@Suppress("UNCHECKED_CAST")
fun auditCvesOf(dependency: Map<String, Any?>): List<Map<String, Any?>> = dependency["cves"] as List<Map<String, Any?>>

/** One `<suppress>` entry of the suppression file: the reason and what it matches. */
class AuditSuppression(val notes: String, val cves: Set<String>, val packageUrl: Regex?)

fun readAuditSuppressions(): List<AuditSuppression> {
    val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(suppressionFileForAudit)
    val nodes = doc.getElementsByTagName("suppress")
    return (0 until nodes.length).map { i ->
        val el = nodes.item(i) as org.w3c.dom.Element
        fun texts(tag: String) = el.getElementsByTagName(tag).let { l -> (0 until l.length).map { l.item(it).textContent.trim() } }
        val purl = el.getElementsByTagName("packageUrl").item(0)
        AuditSuppression(
            notes = texts("notes").firstOrNull()?.replace(Regex("\\s+"), " ").orEmpty(),
            cves = texts("cve").toSet(),
            packageUrl = purl?.let { if (it.attributes.getNamedItem("regex")?.nodeValue == "true") Regex(it.textContent.trim()) else Regex(Regex.escape(it.textContent.trim())) }
        )
    }
}

/** CVEs per "group:name:version" from the Dependency-Check JSON report, open and suppressed. */
fun readAuditCves(reportFile: File): Map<String, List<Map<String, Any?>>> {
    @Suppress("UNCHECKED_CAST")
    val report = groovy.json.JsonSlurper().parse(reportFile) as Map<String, Any?>
    val suppressions = readAuditSuppressions()
    val purlPattern = Regex("""^pkg:maven/([^/]+)/([^@]+)@(.+)$""")
    val out = mutableMapOf<String, MutableMap<String, Map<String, Any?>>>()
    @Suppress("UNCHECKED_CAST")
    for (dep in report["dependencies"] as List<Map<String, Any?>>) {
        val purls = (dep["packages"] as? List<Map<String, Any?>>)?.mapNotNull { it["id"] as? String }.orEmpty()
        for (purl in purls) {
            val m = purlPattern.find(purl) ?: continue
            val coordinate = "${m.groupValues[1]}:${m.groupValues[2]}:${m.groupValues[3]}"
            for ((key, suppressed) in listOf("vulnerabilities" to false, "suppressedVulnerabilities" to true)) {
                for (v in (dep[key] as? List<Map<String, Any?>>).orEmpty()) {
                    val id = v["name"] as String
                    val v3 = v["cvssv3"] as? Map<String, Any?>
                    val v2 = v["cvssv2"] as? Map<String, Any?>
                    val score = (v3?.get("baseScore") ?: v2?.get("score"))?.toString()?.toDoubleOrNull()
                    val severity = (v3?.get("baseSeverity") ?: v2?.get("severity") ?: v["severity"])?.toString()?.uppercase()
                    val reason = if (!suppressed) null else suppressions.firstOrNull { id in it.cves }?.notes
                        ?: suppressions.firstOrNull { it.packageUrl?.containsMatchIn(purl) == true }?.notes
                        ?: "Suppressed in config/dependency-check-suppressions.xml"
                    val url = if (id.startsWith("CVE-")) "https://nvd.nist.gov/vuln/detail/$id" else
                        (v["references"] as? List<Map<String, Any?>>)?.firstOrNull()?.get("url")?.toString() ?: "https://github.com/advisories/$id"
                    out.getOrPut(coordinate) { linkedMapOf() }.putIfAbsent(
                        id,
                        linkedMapOf("id" to id, "score" to score, "severity" to severity, "url" to url, "suppressed" to suppressed, "reason" to reason)
                    )
                }
            }
        }
    }
    return out.mapValues { (_, byId) -> byId.values.sortedByDescending { (it["score"] as? Double) ?: 0.0 } }
}

tasks.register("dependencyAudit") {
    group = "verification"
    description = "Regenerates src/main/assets/dependency-audit.json (ADR-0024) from the resolved release " +
        "classpath and the Dependency-Check report; run :app:dependencyCheckAnalyze first."
    notCompatibleWithConfigurationCache("walks resolved configurations at execution time")

    doLast {
        val reportFile = dependencyCheckReport.get().asFile
        require(reportFile.exists()) {
            "No Dependency-Check report at $reportFile — run ./gradlew :app:dependencyCheckAnalyze " +
                "(needs the NVD API key in nvd.properties) or copy the CI artifact there."
        }
        @Suppress("UNCHECKED_CAST")
        val report = groovy.json.JsonSlurper().parse(reportFile) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val scanInfo = report["scanInfo"] as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val nvdAsOf = (scanInfo["dataSource"] as? List<Map<String, Any?>>)
            ?.firstOrNull { it["name"] == "NVD API Last Modified" }?.get("timestamp")?.toString()
        val components = resolvedReleaseComponents()
        val licenses = pomLicenses(components)
        val cves = readAuditCves(reportFile)
        val dependencies = components
            .sortedWith(compareBy({ it.group }, { it.module }, { it.version }))
            .map { c ->
                val coordinate = "${c.group}:${c.module}:${c.version}"
                linkedMapOf(
                    "group" to c.group,
                    "name" to c.module,
                    "version" to c.version,
                    "licenses" to licenses[coordinate].orEmpty(),
                    "cves" to cves[coordinate].orEmpty()
                )
            }
        val unmatched = cves.keys - dependencies.map { "${it["group"]}:${it["name"]}:${it["version"]}" }.toSet()
        if (unmatched.isNotEmpty()) logger.warn("dependencyAudit: report CVEs on artifacts outside the classpath: $unmatched")
        val snapshot = linkedMapOf(
            "schema" to 1,
            "generatedAt" to LocalDate.now(ZoneOffset.UTC).toString(),
            "nvdDataAsOf" to nvdAsOf,
            "engine" to scanInfo["engineVersion"],
            "dependencies" to dependencies
        )
        dependencyAuditFile.asFile.parentFile.mkdirs()
        dependencyAuditFile.asFile.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(snapshot)) + "\n")
        val open = dependencies.sumOf { d -> auditCvesOf(d).count { it["suppressed"] == false } }
        logger.lifecycle("ADR-0024 audit snapshot written: ${dependencies.size} artifacts, $open open CVEs.")
    }
}

tasks.register("verifyDependencyAudit") {
    group = "verification"
    description = "Fails if the committed dependency audit snapshot (ADR-0024) does not match the resolved " +
        "release classpath, or — when a Dependency-Check report is present — lists a different set of open CVEs."
    notCompatibleWithConfigurationCache("walks resolved configurations at execution time")

    doLast {
        val file = dependencyAuditFile.asFile
        require(file.exists()) { "Missing $file — run ./gradlew :app:dependencyCheckAnalyze :app:dependencyAudit" }
        @Suppress("UNCHECKED_CAST")
        val snapshot = groovy.json.JsonSlurper().parse(file) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val deps = snapshot["dependencies"] as List<Map<String, Any?>>
        val snapshotCoordinates = deps.map { "${it["group"]}:${it["name"]}:${it["version"]}" }.toSet()
        val resolved = resolvedReleaseComponents().map { "${it.group}:${it.module}:${it.version}" }.toSet()
        val problems = mutableListOf<String>()
        (resolved - snapshotCoordinates).sorted().forEach { problems += "on the classpath, not in the snapshot: $it" }
        (snapshotCoordinates - resolved).sorted().forEach { problems += "in the snapshot, not on the classpath: $it" }

        val reportFile = dependencyCheckReport.get().asFile
        if (reportFile.exists()) {
            fun openIds(cves: List<Map<String, Any?>>) = cves.filter { it["suppressed"] == false }.map { it["id"] as String }.toSet()
            val snapshotOpen = deps.flatMap { d -> openIds(auditCvesOf(d)).map { "${d["group"]}:${d["name"]}:${d["version"]} $it" } }.toSet()
            val fresh = readAuditCves(reportFile)
            val freshOpen = fresh.flatMap { (c, cves) -> openIds(cves).map { "$c $it" } }.toSet()
            (freshOpen - snapshotOpen).sorted().forEach { problems += "open CVE not in the snapshot: $it" }
            (snapshotOpen - freshOpen).sorted().forEach { logger.warn("verifyDependencyAudit: snapshot lists an open CVE the scan no longer reports: $it") }
            val snapshotSuppressed = deps.flatMap { d -> auditCvesOf(d).filter { it["suppressed"] == true }.map { "${d["group"]}:${d["name"]}:${d["version"]} ${it["id"]}" } }.toSet()
            val freshSuppressed = fresh.flatMap { (c, cves) -> cves.filter { it["suppressed"] == true }.map { "$c ${it["id"]}" } }.toSet()
            if (snapshotSuppressed != freshSuppressed) logger.warn("verifyDependencyAudit: suppressed CVE set drifted; regenerate the snapshot when convenient (${(freshSuppressed - snapshotSuppressed).size} new, ${(snapshotSuppressed - freshSuppressed).size} gone)")
        }

        if (problems.isNotEmpty()) {
            throw GradleException(
                "ADR-0024 — dependency audit snapshot is stale; run ./gradlew :app:dependencyCheckAnalyze :app:dependencyAudit and commit:\n  - " +
                    problems.joinToString("\n  - ")
            )
        }
        logger.lifecycle("ADR-0024 dependency audit snapshot matches: ${resolved.size} artifacts" + if (reportFile.exists()) ", open CVEs match the scan." else ".")
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

        // ADR-0004 (amended): neither the sync-adapter nor the authenticator service
        // may be exported; the system binds them as the system uid regardless.
        val internalServices = listOf(
            "io.pcontacts.app.sync.ProtonSyncService",
            "io.pcontacts.app.account.ProtonAuthenticatorService"
        )
        fun checkServicesNotExported(variant: String, file: java.io.File) {
            val text = file.readText()
            for (service in internalServices) {
                val block = Regex("""<service\b[^>]*android:name="${Regex.escape(service)}"[^>]*>""")
                    .find(text)?.value
                if (block == null) {
                    violations += "$variant: <service> $service not found in merged manifest"
                    continue
                }
                val exported = Regex("""android:exported\s*=\s*"([^"]*)"""").find(block)?.groupValues?.get(1)
                if (exported != "false") {
                    violations += "$variant: $service must be android:exported=\"false\", got \"$exported\""
                }
            }
        }

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
            checkServicesNotExported("debug", debugManifest)
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
            checkServicesNotExported("release", releaseManifest)
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
    // Pin the report location. The plugin's default moved to a
    // dependency-check/ subdirectory in 13.0.0; CI reads these paths, so
    // state them here rather than tracking the plugin's default.
    outputDirectory.set(layout.buildDirectory.dir("reports/dependency-check"))
    // Scan only what actually ships in the release APK. Test/build/lint
    // classpaths pull in transitives (gRPC, Netty, protobuf, kotlin-compiler)
    // with their own CVE histories, none of which reach end users.
    scanConfigurations = listOf("releaseRuntimeClasspath")
    // Locally the key lives in the gitignored nvd.properties (`nvd.apiKey=...`).
    val nvdKeyFile = rootProject.file("nvd.properties")
    val nvdKeyFromFile: String? = if (nvdKeyFile.exists()) {
        Properties().also { props -> nvdKeyFile.inputStream().use { props.load(it) } }.getProperty("nvd.apiKey")
    } else {
        null
    }
    val nvdKey: String = System.getenv("NVD_API_KEY") ?: nvdKeyFromFile?.trim().orEmpty()
    nvd.apiKey = nvdKey
    // NVD's API returns intermittent 503/timeout responses. Bump retry count
    // and inter-request delay enough to survive a brief blip, but not so much
    // that a sustained NVD outage runs past the CI job timeout.
    nvd.maxRetryCount = 20
    nvd.delay = 2000
    // Trust cached NVD data for 7 days. We run weekly, so a single failed
    // refresh still produces a useful report against the prior week's CVE set.
    nvd.validForHours = 168
}

afterEvaluate {
    tasks.matching { it.name == "assembleRelease" }.configureEach {
        dependsOn(":core:proton-api:verifyCertificatePins")
        dependsOn("verifyManifestInvariants")
    }
    tasks.matching { it.name.contains("VersionControlInfo") }.configureEach {
        enabled = false
    }
}
