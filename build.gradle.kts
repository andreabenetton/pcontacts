// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.android.lint) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.dependency.check) apply false
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.kover) apply false
}

apply(plugin = "org.jetbrains.kotlinx.kover")

dependencies {
    subprojects.filter { it.path != ":tools:lint" }.forEach {
        "kover"(it)
    }
}

subprojects {
    if (path != ":tools:lint") {
        apply(plugin = "org.jetbrains.kotlinx.kover")
    }
    apply(plugin = "io.gitlab.arturbosch.detekt")
    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        parallel = true
        buildUponDefaultConfig = true
        config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
    }
    dependencies {
        "detektPlugins"("io.gitlab.arturbosch.detekt:detekt-formatting:${rootProject.libs.versions.detekt.get()}")
    }
}

// =============================================================================
// ADR-0015 enforcement — fail the build if a forbidden artifact lands in any
// module's resolved release classpath. F-Droid pre-flight gate.
// =============================================================================
//
// Catches:
//   - Google Play Services, Firebase, Google Ads, Play Integrity etc.
//   - Common analytics SDKs (Crashlytics, Sentry, AppsFlyer, Bugsnag).
//
// What it does NOT catch (license scanning handled separately by the
// `checkLicense` task in :app — see config/allowed-licenses.json):
//   - Non-GPL-3-compatible licenses on otherwise allowed groups.
//   - Transitive deps from disallowed sub-coordinates of an allowed group.

val forbiddenGroups = listOf(
    "com.google.android.gms",
    "com.google.firebase",
    "com.google.android.play",
    "com.google.android.ads",
    "com.google.gms",
    "com.google.ads",
    "com.google.android.libraries.places",
    "io.sentry",
    "com.bugsnag",
    "com.appsflyer",
    "com.crashlytics",
    "io.fabric"
)

tasks.register("checkForbiddenDependencies") {
    group = "verification"
    description = "Fails the build if any sub-project's resolved release " +
        "classpath contains a group on the ADR-0015 forbidden list."
    // Walks subprojects' resolved configurations at execution time — that's
    // fundamentally configuration-cache-incompatible. Opt out cleanly so the
    // CC entry isn't discarded (which would otherwise show up as "BUILD
    // FAILED" even though the task itself succeeded).
    notCompatibleWithConfigurationCache("walks resolved configurations at execution time")

    doLast {
        val violations = mutableListOf<String>()
        subprojects.forEach { sub ->
            sub.configurations.matching { cfg ->
                // Only inspect runtime classpaths the APK actually ships.
                cfg.name == "releaseRuntimeClasspath" ||
                    cfg.name == "runtimeClasspath" ||
                    cfg.name.endsWith("ReleaseRuntimeClasspath")
            }.forEach { config ->
                if (!config.isCanBeResolved) return@forEach
                runCatching { config.resolvedConfiguration.firstLevelModuleDependencies }
                    .getOrNull()?.let { deps -> walkDeps(sub.name, config.name, deps, forbiddenGroups, violations) }
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "ADR-0015 — forbidden dependencies detected:\n  - " +
                    violations.distinct().sorted().joinToString("\n  - ")
            )
        }
        logger.lifecycle("ADR-0015 check passed — no forbidden dependencies in release classpaths.")
    }
}

fun walkDeps(
    module: String,
    config: String,
    deps: Set<org.gradle.api.artifacts.ResolvedDependency>,
    blocked: List<String>,
    sink: MutableList<String>,
    seen: MutableSet<String> = mutableSetOf()
) {
    for (d in deps) {
        val key = "${d.moduleGroup}:${d.moduleName}:${d.moduleVersion}"
        if (key in seen) continue
        seen += key
        if (blocked.any { d.moduleGroup == it || d.moduleGroup.startsWith("$it.") }) {
            sink += "$module/$config → $key"
        }
        walkDeps(module, config, d.children, blocked, sink, seen)
    }
}
