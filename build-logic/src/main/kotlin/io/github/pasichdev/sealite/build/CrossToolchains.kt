// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 pasichdev

package io.github.pasichdev.sealite.build

import java.io.File

/** How `jni.h` / `jni_md.h` are provided for a target. */
enum class JniKind { WINDOWS, UNIX, ANDROID }

/** The seven non-Apple targets that are cross-compiled from the (macOS) build host. */
enum class CrossTarget(
    /** Directory name under `build/openssl/` and `build/native/cross/`. */
    val dirName: String,
    val libName: String,
    /** `./Configure <this>` for OpenSSL. */
    val opensslTarget: String,
    val jni: JniKind,
    /** Android only: the ABI directory name under `jniLibs/` and the clang triple prefix. */
    val androidAbi: String? = null,
    val androidTriple: String? = null,
) {
    WINDOWS_X64("windows_x64", "sealite_jni.dll", "mingw64", JniKind.WINDOWS),
    LINUX_X64("linux_x64", "libsealite_jni.so", "linux-x86_64", JniKind.UNIX),
    LINUX_ARM64("linux_arm64", "libsealite_jni.so", "linux-aarch64", JniKind.UNIX),
    ANDROID_ARM64(
        "android_arm64-v8a",
        "libsealite_jni.so",
        "android-arm64",
        JniKind.ANDROID,
        "arm64-v8a",
        "aarch64-linux-android",
    ),
    ANDROID_ARM32(
        "android_armeabi-v7a",
        "libsealite_jni.so",
        "android-arm",
        JniKind.ANDROID,
        "armeabi-v7a",
        "armv7a-linux-androideabi",
    ),
    ANDROID_X64(
        "android_x86_64",
        "libsealite_jni.so",
        "android-x86_64",
        JniKind.ANDROID,
        "x86_64",
        "x86_64-linux-android",
    ),
    ANDROID_X86("android_x86", "libsealite_jni.so", "android-x86", JniKind.ANDROID, "x86", "i686-linux-android"),
    ;

    val isElf: Boolean get() = jni != JniKind.WINDOWS

    /** `WindowsX64`, `AndroidArm64` ... for task names. */
    val taskSuffix: String get() = name.split('_').joinToString("") { it.lowercase().replaceFirstChar(Char::uppercase) }
}

/** Everything the build tasks need to drive one target's compiler. Paths are absolute. */
class CrossToolchain(
    val target: CrossTarget,
    val cc: File,
    val ar: File,
    /** Flags for every compile (and the link): `--target`, `--sysroot`, `--gcc-toolchain`, `-fPIC`. */
    val compileArgs: List<String>,
    /** Flags for the link only, before the objects. */
    val linkArgs: List<String>,
    /** Libraries after the objects and `libcrypto.a`. */
    val linkLibs: List<String>,
    /** Extra environment for OpenSSL's Configure/make (merged over the inherited one). */
    val opensslEnv: Map<String, String>,
    /** Extra directories prepended to PATH for OpenSSL. */
    val opensslPath: List<File>,
    val opensslConfigureArgs: List<String>,
    /** Changes whenever the compiler or sysroot changes: an up-to-date key. */
    val fingerprint: String,
)

/** Binutils-like tools that can read ELF and PE files. */
class CrossBinutils(
    val nm: File,
    val objdump: File,
)

/**
 * Finds the cross compilers on the build host. Nothing is downloaded: Kotlin/Native's LLVM and its
 * mingw / glibc sysroots come from `~/.konan` (or `KONAN_DATA_DIR`), which a Kotlin/Native build
 * populates, and the Android NDK from the SDK.
 */
object CrossToolchains {
    const val ANDROID_API = 24
    const val MIN_NDK_MAJOR = 28

    fun konanHome(env: Map<String, String> = System.getenv()): File =
        File(env["KONAN_DATA_DIR"] ?: (System.getProperty("user.home") + "/.konan"))

    private fun konanDependency(prefix: String, probe: String, env: Map<String, String>): File {
        val deps = konanHome(env).resolve("dependencies")
        val found =
            deps
                .listFiles()
                .orEmpty()
                .filter { it.isDirectory && it.name.startsWith(prefix) && it.resolve(probe).exists() }
                .sortedWith(compareBy(naturalOrder<String>()) { naturalKey(it.name) })
                .lastOrNull()
        return found ?: error(
            "No '$prefix*' under $deps (needs $probe). Kotlin/Native downloads it on first use: run " +
                ":internal:konan-sysroots:downloadKonanSysroots (the cross tasks depend on it), " +
                "or point KONAN_DATA_DIR at a populated directory.",
        )
    }

    /** `llvm-21-x` < `llvm-9-x` fixed: zero-pad every number. */
    private fun naturalKey(name: String): String = Regex("\\d+").replace(name) { it.value.padStart(8, '0') }

    private fun konanLlvm(env: Map<String, String>): File = konanDependency("llvm-", "bin/clang", env)

    private fun llvmBin(env: Map<String, String>): File = konanLlvm(env).resolve("bin")

    // ---- Android NDK -----------------------------------------------------------------------------

    private fun ndkHostTag(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            "mac" in os -> "darwin-x86_64"
            "win" in os -> "windows-x86_64"
            else -> "linux-x86_64"
        }
    }

    private fun sdkRoots(rootDir: File, env: Map<String, String>): List<File> {
        val fromLocalProperties =
            rootDir
                .resolve("local.properties")
                .takeIf { it.isFile }
                ?.readLines()
                ?.map { it.trim() }
                ?.firstOrNull { it.startsWith("sdk.dir") }
                ?.substringAfter('=')
                ?.trim()
                ?.replace("\\:", ":")
                ?.replace("\\\\", "\\")
        val home = System.getProperty("user.home")
        // The IDE's default install locations come last.
        val defaults =
            listOf("$home/Library/Android/sdk", "$home/Android/Sdk", env["LOCALAPPDATA"]?.let { "$it/Android/Sdk" })
        return listOfNotNull(env["ANDROID_HOME"], env["ANDROID_SDK_ROOT"], fromLocalProperties).map(::File) +
            defaults.filterNotNull().map(::File)
    }

    private fun ndkMajor(dir: File): Int? {
        val revision =
            dir
                .resolve("source.properties")
                .takeIf { it.isFile }
                ?.readLines()
                ?.firstOrNull { it.startsWith("Pkg.Revision") }
                ?.substringAfter('=')
                ?.trim()
        return (revision ?: dir.name).substringBefore('.').toIntOrNull()
    }

    /**
     * `ANDROID_NDK_HOME` if it is set and is NDK 28+, otherwise the newest NDK (28+) installed under
     * `$ANDROID_HOME/ndk`, `$ANDROID_SDK_ROOT/ndk`, `sdk.dir` of `local.properties` or the IDE's default SDK directory.
     */
    fun androidNdk(rootDir: File, env: Map<String, String> = System.getenv()): File {
        env["ANDROID_NDK_HOME"]
            ?.let(::File)
            ?.takeIf {
                (ndkMajor(it) ?: 0) >= MIN_NDK_MAJOR &&
                    it.resolve("toolchains/llvm").isDirectory
            }?.let { return it }
        val installed =
            sdkRoots(rootDir, env)
                .flatMap {
                    it
                        .resolve("ndk")
                        .listFiles()
                        .orEmpty()
                        .toList()
                }.filter {
                    it.isDirectory && (ndkMajor(it) ?: 0) >= MIN_NDK_MAJOR &&
                        it.resolve("toolchains/llvm").isDirectory
                }.sortedWith(compareBy(naturalOrder<String>()) { naturalKey(it.name) })
        return installed.lastOrNull() ?: error(
            "No Android NDK $MIN_NDK_MAJOR+ found: set ANDROID_NDK_HOME, or install one under ANDROID_HOME/ndk " +
                "or the sdk.dir of local.properties.",
        )
    }

    private fun ndkBin(ndk: File): File = ndk.resolve("toolchains/llvm/prebuilt/${ndkHostTag()}/bin")

    // ---- Resolution ------------------------------------------------------------------------------

    /** Compile flags of the Windows and Linux builds (OpenSSL, SQLCipher and the glue alike). */
    private val hardenedCompile = listOf("-fstack-protector-strong", "-D_FORTIFY_SOURCE=2", "-O2")

    /** Changes with the flags too, so a hardening change re-runs the builds. */
    private fun fingerprintOf(base: String, vararg flags: List<String>): String = "$base;flags=" +
        flags
            .toList()
            .flatten()
            .joinToString(" ")
            .hashCode()
            .toUInt()
            .toString(16)

    fun resolve(target: CrossTarget, rootDir: File, env: Map<String, String> = System.getenv()): CrossToolchain {
        val elfLink = listOf("-Wl,--exclude-libs,ALL", "-Wl,-z,noexecstack", "-Wl,--strip-all")
        // Full RELRO: the GOT is read-only once the loader has relocated the library.
        val linuxLink = listOf("-shared", "-Wl,-z,relro,-z,now") + elfLink
        // ASLR (high-entropy for 64-bit address space) and DEP.
        val windowsLink = listOf("-shared", "-static-libgcc", "-Wl,--dynamicbase,--nxcompat,--high-entropy-va")
        // The stack protector needs libssp; link its static archive, not the libssp-0.dll import library,
        // so the DLL has no dependency beyond the system libraries (VerifyCrossExportsTask checks that).
        val windowsLibs =
            listOf("-Wl,-Bstatic", "-lssp", "-Wl,-Bdynamic") +
                listOf("-lws2_32", "-lcrypt32", "-ladvapi32", "-luser32", "-lbcrypt")
        return when (target) {
            CrossTarget.WINDOWS_X64 -> {
                val llvm = llvmBin(env)
                val mingw = konanDependency("msys2-mingw-w64-x86_64-", "x86_64-w64-mingw32", env)
                CrossToolchain(
                    target = target,
                    cc = llvm.resolve("clang"),
                    ar = llvm.resolve("llvm-ar"),
                    compileArgs =
                        listOf("--target=x86_64-w64-mingw32", "--sysroot=${mingw.path}", "-fuse-ld=lld") +
                            hardenedCompile,
                    linkArgs = windowsLink,
                    linkLibs = windowsLibs,
                    opensslEnv = emptyMap(),
                    opensslPath = emptyList(),
                    opensslConfigureArgs = listOf("--cross-compile-prefix="),
                    fingerprint =
                        fingerprintOf(
                            "clang=${konanLlvm(env).name};sysroot=${mingw.name}",
                            hardenedCompile,
                            windowsLink,
                            windowsLibs,
                        ),
                )
            }

            CrossTarget.LINUX_X64, CrossTarget.LINUX_ARM64 -> {
                val llvm = llvmBin(env)
                val arch = if (target == CrossTarget.LINUX_X64) "x86_64" else "aarch64"
                val gcc = konanDependency("$arch-unknown-linux-gnu-gcc-", "$arch-unknown-linux-gnu/sysroot", env)
                CrossToolchain(
                    target = target,
                    cc = llvm.resolve("clang"),
                    ar = llvm.resolve("llvm-ar"),
                    compileArgs =
                        listOf(
                            "--target=$arch-unknown-linux-gnu",
                            "--sysroot=${gcc.resolve("$arch-unknown-linux-gnu/sysroot").path}",
                            "--gcc-toolchain=${gcc.path}",
                            "-fuse-ld=lld",
                            "-fPIC",
                        ) + hardenedCompile,
                    linkArgs = linuxLink,
                    linkLibs = listOf("-lm", "-ldl", "-lpthread"),
                    opensslEnv = emptyMap(),
                    opensslPath = emptyList(),
                    opensslConfigureArgs = listOf("--cross-compile-prefix="),
                    fingerprint =
                        fingerprintOf("clang=${konanLlvm(env).name};sysroot=${gcc.name}", hardenedCompile, linuxLink),
                )
            }

            CrossTarget.ANDROID_ARM64, CrossTarget.ANDROID_ARM32, CrossTarget.ANDROID_X64, CrossTarget.ANDROID_X86 -> {
                val ndk = androidNdk(rootDir, env)
                val bin = ndkBin(ndk)
                CrossToolchain(
                    target = target,
                    cc = bin.resolve("${target.androidTriple}$ANDROID_API-clang"),
                    ar = bin.resolve("llvm-ar"),
                    compileArgs = listOf("-fPIC"),
                    linkArgs = listOf("-shared", "-Wl,-z,max-page-size=16384", "-Wl,--no-undefined") + elfLink,
                    // libm / libdl / liblog are not linked implicitly into a shared library.
                    linkLibs = listOf("-llog", "-lm", "-ldl"),
                    opensslEnv = mapOf("ANDROID_NDK_ROOT" to ndk.path, "ANDROID_NDK_HOME" to ndk.path),
                    opensslPath = listOf(bin),
                    opensslConfigureArgs = listOf("-D__ANDROID_API__=$ANDROID_API"),
                    fingerprint = "ndk=${ndk.name};api=$ANDROID_API",
                )
            }
        }
    }

    /**
     * `llvm-nm` and `llvm-objdump`, which read ELF and PE. Kotlin/Native's LLVM "essentials" bundle
     * ships neither, so the NDK's copies come first; then konan's, Homebrew's and the PATH's.
     */
    fun binutils(rootDir: File, env: Map<String, String> = System.getenv()): CrossBinutils {
        val dirs =
            buildList {
                runCatching { add(ndkBin(androidNdk(rootDir, env))) }
                runCatching { add(llvmBin(env)) }
                add(File("/opt/homebrew/opt/llvm/bin"))
                add(File("/usr/local/opt/llvm/bin"))
                env["PATH"]?.split(File.pathSeparatorChar)?.forEach { add(File(it)) }
            }

        fun find(name: String) = dirs.map { it.resolve(name) }.firstOrNull { it.canExecute() }
            ?: error("No $name found (looked in the NDK, konan LLVM, Homebrew LLVM and PATH).")
        return CrossBinutils(find("llvm-nm"), find("llvm-objdump"))
    }
}
