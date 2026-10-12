import gobley.gradle.GobleyHost
import gobley.gradle.Variant
import gobley.gradle.cargo.dsl.jvm
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.net.URI
import java.security.MessageDigest

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinComposeCompiler)
    alias(libs.plugins.kotlinAtomicfu)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.gobleyCargo)
    alias(libs.plugins.gobleyUniffi)
}

// ── The Rust source, materialized from the registry ─────────────────────────────
//
// The application builds the published `qualia2fa-ffi` crate, not a checkout of the FFI
// repository: this repository has no submodules, and the version it builds is the version
// the registry serves under that number. The crate is fetched once, checked against the
// SHA-256 the registry index publishes for it, and unpacked into `rust/`, which git
// ignores. Deleting `rust/` re-materializes it.
val ffiCrateVersion = providers.gradleProperty("qualia2fa.ffi.version").getOrElse("0.1.3")
val ffiCrateSha256 = "a070a34af3de237635c52aca52da86d1f15f24fa998637cdd20cb5191bc68934"
val rustSourceDir = layout.projectDirectory.dir("rust").asFile
val rustManifest = rustSourceDir.resolve("Cargo.toml")

// The materialized copy is only reused when it is the version this build asks for;
// otherwise a version bump would silently keep building the old crate.
val materializedVersion =
    if (rustManifest.isFile) {
        Regex("""(?m)^version = "([^"]+)"""")
            .find(rustManifest.readText())?.groupValues?.get(1)
    } else {
        null
    }

if (materializedVersion != ffiCrateVersion) {
    if (rustSourceDir.exists()) rustSourceDir.deleteRecursively()
    rustSourceDir.mkdirs()
    val crateFile = layout.buildDirectory.file("crates/qualia2fa-ffi-$ffiCrateVersion.crate").get().asFile
    if (!crateFile.isFile) {
        crateFile.parentFile.mkdirs()
        logger.lifecycle("Downloading qualia2fa-ffi $ffiCrateVersion from crates.io")
        URI("https://static.crates.io/crates/qualia2fa-ffi/qualia2fa-ffi-$ffiCrateVersion.crate")
            .toURL().openStream().use { input -> crateFile.outputStream().use { output -> input.copyTo(output) } }
    }
    val actual = MessageDigest.getInstance("SHA-256").digest(crateFile.readBytes())
        .joinToString("") { "%02x".format(it) }
    require(actual == ffiCrateSha256) {
        "qualia2fa-ffi $ffiCrateVersion has SHA-256 $actual, expected $ffiCrateSha256"
    }
    copy {
        from(tarTree(resources.gzip(crateFile))) {
            eachFile {
                // The archive holds everything under `qualia2fa-ffi-<version>/`.
                relativePath = RelativePath(true, *relativePath.segments.drop(1).toTypedArray())
            }
        }
        into(rustSourceDir)
    }
    // Gobley 0.3.7 generates bindings against UniFFI 0.29.4, so the patch version is
    // pinned rather than left to the newest 0.29.x. The FFI manifest will carry the same
    // pin from its next release on; until then the lock file of the unpacked crate is
    // rewritten here.
    providers.exec {
        commandLine("cargo", "update", "-p", "uniffi", "--precise", "0.29.4")
        workingDir = rustSourceDir
    }.result.get()
}

kotlin {
    jvm()
    jvmToolchain(21)

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

// The Rust source lives in `rust/`, materialized from the published `qualia2fa-ffi`
// crate by the `materializeRustSource` task in the root build script.
cargo {
    packageDirectory = layout.projectDirectory.dir("rust")

    // Gobley defaults the JVM variant to Debug, which is the wrong default for an
    // application project: `subtle`'s debug assertions branch on secret data and the
    // library targets release builds, so a debug build must never be what gets shipped
    // or what the IDE runs by default. Ask for one explicitly with
    // `-Pqualia2fa.jvm.variant=debug` when you actually want it.
    jvmVariant = Variant((findProperty("qualia2fa.jvm.variant") ?: "release").toString())

    builds.jvm {
        // Only the host's Rust target is embedded: the Windows desktop build loads the
        // library through JNA, and the other platforms are M6 work.
        embedRustLibrary = (rustTarget == GobleyHost.current.rustTarget)
    }
}

uniffi {
    generateFromLibrary {
        // The UniFFI namespace is the Rust crate name with underscores.
        namespace = "qualia2fa_ffi"
        packageName = "dev.qualia.qualia2fa.ffi"
    }
}

compose.desktop {
    application {
        mainClass = "dev.qualia.qualia2fa.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Qualia2FA"
            packageVersion = "1.0.0"
            description = "Serverless, non-realtime two-factor authentication"
            vendor = "Qualia2FA"
        }
    }
}
