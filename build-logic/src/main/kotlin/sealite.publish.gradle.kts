// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar
import org.gradle.api.publish.PublishingExtension

plugins {
    id("com.vanniktech.maven.publish")
    id("org.jetbrains.dokka")
}

// The release builds the Maven repository once, into a local directory, and everything after that
// (consumer smoke test, attestation, upload) works on exactly those files.
// `./gradlew publishAllPublicationsToStagingRepository [-Psealite.stagingDir=<dir>]`
extensions.configure<PublishingExtension> {
    repositories {
        maven {
            name = "Staging"
            url =
                providers
                    .gradleProperty("sealite.stagingDir")
                    .map { file(it).toURI() }
                    .orElse(
                        rootProject.layout.buildDirectory
                            .dir("staging-repo")
                            .map { it.asFile.toURI() },
                    ).get()
        }
    }
}

mavenPublishing {
    configure(
        KotlinMultiplatform(
            javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml"),
            sourcesJar = SourcesJar.Sources(),
        ),
    )

    // Central Portal; release is confirmed by hand in the portal UI.
    publishToMavenCentral()
    // Signing needs a key: the release signs the staged files in its gated publish job (see
    // .github/scripts/central-upload.sh), so a build without a key (CI staging, local checks) is unsigned.
    if (providers.gradleProperty("signingInMemoryKey").isPresent ||
        providers.gradleProperty("signing.keyId").isPresent
    ) {
        signAllPublications()
    }

    coordinates("io.github.pasichdev", "sealite", project.version.toString())

    pom {
        name.set("Sealite")
        description.set("SQLCipher-encrypted androidx.sqlite driver for Kotlin Multiplatform")
        inceptionYear.set("2026")
        url.set("https://github.com/pasichdev/sealite")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
                comments.set(
                    "Bundled SQLCipher is BSD-3-Clause (Zetetic); bundled OpenSSL is Apache-2.0. See NOTICE.",
                )
            }
        }
        developers {
            developer {
                id.set("pasichdev")
                name.set("pasichdev")
                url.set("https://github.com/pasichdev")
            }
        }
        scm {
            url.set("https://github.com/pasichdev/sealite")
            connection.set("scm:git:git://github.com/pasichdev/sealite.git")
            developerConnection.set("scm:git:ssh://git@github.com/pasichdev/sealite.git")
        }
        issueManagement {
            system.set("GitHub")
            url.set("https://github.com/pasichdev/sealite/issues")
        }
    }
}
