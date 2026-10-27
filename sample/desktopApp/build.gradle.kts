import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
}

val mainClassName = "io.github.pasichdev.sealite.sample.MainKt"

compose.desktop {
    application {
        mainClass = mainClassName
        // Native code runs in this JVM.
        jvmArgs += "--enable-native-access=ALL-UNNAMED"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "SealiteSample"
            packageVersion = "1.0.0"
            description = "Sealite sample: an encrypted Room database on every platform"
            // jlink image needs java.sql-free modules only; add more if the packaged app reports a missing module.
            modules("java.naming")

            // Files under this directory are copied next to the packaged app and exposed to the
            // JVM as `compose.application.resources.dir`, where Sealite's loader looks for the
            // signed native library first (before the copy inside its jar).
            // TODO(M3): place the signed/notarized libsealite_jni there per OS, e.g.
            //  appResources/macos-arm64/libsealite_jni.dylib, appResources/windows-x64/sealite_jni.dll,
            //  appResources/linux-x64/libsealite_jni.so (the subdirectory names are the ones the
            //  Compose plugin uses: macos-x64, macos-arm64, windows-x64, linux-x64, linux-arm64).
            //  Until then the packaged app falls back to the checksum-verified copy in the jar.
            appResourcesRootDir.set(layout.projectDirectory.dir("appResources"))
        }
    }
}

// Headless verification: runs the self-check in a throwaway directory and exits non-zero on failure.
//   ./gradlew :desktopApp:runSelfCheck
tasks.register<JavaExec>("runSelfCheck") {
    group = "verification"
    description = "Runs the sample's self-check without a window."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = mainClassName
    args("--selfcheck")
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    systemProperty("sealite.sample.dir", layout.buildDirectory.dir("selfcheck-data").get().asFile.absolutePath)
}
