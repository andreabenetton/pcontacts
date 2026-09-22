// SPDX-License-Identifier: GPL-3.0-only
// SPDX-FileCopyrightText: 2026 pcontacts contributors

plugins {
    alias(libs.plugins.kotlin.jvm)
    // ADR-0017 §3 amendment: the merge base is persisted as versioned JSON of the decrypted model.
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // ADR-0005: vCard 4.0 parser/serializer. Apache 2.0 → GPL-3.0 compatible.
    implementation(libs.ezvcard)

    // ez-vcard pulls freemarker 2.3.34 transitively (its hCard/HTML template
    // engine). We never use that feature, but freemarker must stay on the
    // classpath — ez-vcard's writers reference freemarker.template types at
    // link time even on the plain vCard path. Force 2.3.35, which fixes the
    // path-traversal CVE-2026-84939 (affects freemarker < 2.3.35).
    constraints {
        implementation(libs.freemarker) {
            because("CVE-2026-84939 path-traversal in freemarker < 2.3.35")
        }
        // Same story for ez-vcard's jCard (jackson-core) and hCard (jsoup) support, which
        // this app never calls: keep them at versions without open advisories (found by
        // the ADR-0025 runtime check against osv.dev on 2026-09-22).
        implementation(libs.jackson.core) {
            because("GHSA-72hv-8253-57qq and GHSA-r7wm-3cxj-wff9 in jackson-core < 2.21.4")
        }
        implementation(libs.jsoup) {
            because("GHSA-pmhh-3w7g-xqp8 (CVE-2026-71497) in jsoup < 1.23.1")
        }
    }

    // ContactDto / ContactCardDto live here; pulling :core:proton-api in as
    // an api dep so downstream callers (e.g. a future contact-decrypt sync
    // engine) can name those types without re-declaring the dependency.
    api(project(":core:proton-api"))

    implementation(project(":core:logging"))
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
