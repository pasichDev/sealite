// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import com.vanniktech.maven.publish.SourcesJar

plugins {
    id("com.vanniktech.maven.publish")
    id("org.jetbrains.dokka")
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
    signAllPublications()

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
