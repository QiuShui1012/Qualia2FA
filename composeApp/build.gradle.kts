import gobley.gradle.GobleyHost
import gobley.gradle.Variant
import gobley.gradle.cargo.dsl.android
import gobley.gradle.cargo.dsl.jvm
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.Properties

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    // The Android plugin is applied before the Compose plugins: Compose Multiplatform sets
    // up its Android resources publication while it is applied, and with the Android plugin
    // still to come that setup runs before the Android plugin has registered itself, which
    // ends in "Can't infer current AndroidGradlePluginVersion: Is the Android plugin
    // applied?" from the Kotlin plugin.
    alias(libs.plugins.androidApplication)
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
//
// A development build can point at a local checkout instead, with
// `-Pqualia2fa.ffi.localDir=<path to the FFI repository>`, relative to the repository root.
// A change to the FFI cannot be built against the registry before it is released, and testing
// it through a released version is not testing it at all. The registry stays the default,
// checksum and all, so an ordinary build still builds exactly what crates.io serves under that
// version number.
val ffiCrateVersion = providers.gradleProperty("qualia2fa.ffi.version").getOrElse("0.1.10")
val ffiCrateSha256 = "b81d09db4cb1820e59d5ebc3abf03f6bd2c66524ad1a6982ace318be3039afc7"
val ffiLocalDir = providers.gradleProperty("qualia2fa.ffi.localDir").orNull
val rustSourceDir = ffiLocalDir?.let { rootProject.file(it) } ?: layout.projectDirectory.dir("rust").asFile
val rustManifest = rustSourceDir.resolve("Cargo.toml")

require(ffiLocalDir == null || rustManifest.isFile) {
    "qualia2fa.ffi.localDir points at $rustSourceDir, which holds no Cargo.toml"
}

// The materialized copy is only reused when it is the version this build asks for;
// otherwise a version bump would silently keep building the old crate.
val materializedVersion =
    if (rustManifest.isFile) {
        Regex("""(?m)^version = "([^"]+)"""")
            .find(rustManifest.readText())?.groupValues?.get(1)
    } else {
        null
    }

if (ffiLocalDir == null && materializedVersion != ffiCrateVersion) {
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
    // The Android target is a separate platform, not a flavor of the desktop one: it gets
    // its own source set, its own entry point and its own platform key store. What the two
    // share — the file-backed storage, the clock, the encodings — lives in `androidJvmMain`,
    // which both depend on, so that "how the vault is written" exists once.
    androidTarget {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }
    jvmToolchain(21)

    sourceSets {
        // The source set the JVM and Android targets have in common. It is declared here
        // rather than by the default hierarchy, which keeps `jvmMain` and `androidMain` as
        // siblings.
        val androidJvmMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation(compose.runtime)
            }
        }
        jvmMain {
            dependsOn(androidJvmMain)
        }
        androidMain {
            dependsOn(androidJvmMain)
            dependencies {
                implementation(libs.androidxActivityCompose)
                // The code scanner: a preview surface, the camera bound to the activity's
                // life, and the analysis that hands frames to the core's decoder.
                implementation(libs.androidxCameraCore)
                implementation(libs.androidxCameraCamera2)
                implementation(libs.androidxCameraLifecycle)
                implementation(libs.androidxCameraView)
                // UniFFI's Kotlin bindings load the Rust library through JNA. The `aar`
                // flavor is the one that carries JNA's dispatcher for Android, and 5.17 or
                // newer is what understands 16 KB page sizes.
                implementation("net.java.dev.jna:jna:${libs.versions.jna.get()}@aar")
            }
        }
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            // The user-visible text lives in `composeResources`, so the application can be
            // read in more than one language. English is the default locale (`values/`),
            // Chinese is a translation (`values-zh/`).
            implementation(compose.components.resources)
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
// crate by the `materializeRustSource` task in the root build script, or in the local
// checkout named by `qualia2fa.ffi.localDir`.
cargo {
    packageDirectory = layout.dir(providers.provider { rustSourceDir })

    // Gobley defaults the JVM variant to Debug, which is the wrong default for an
    // application project: `subtle`'s debug assertions branch on secret data and the
    // library targets release builds, so a debug build must never be what gets shipped
    // or what the IDE runs by default. Ask for one explicitly with
    // `-Pqualia2fa.jvm.variant=debug` when you actually want it.
    jvmVariant = Variant((findProperty("qualia2fa.jvm.variant") ?: "release").toString())

    builds.jvm {
        // Only the host's Rust target is embedded: the Windows desktop build loads the
        // library through JNA, and the other desktop platforms are not shipped yet.
        embedRustLibrary = (rustTarget == GobleyHost.current.rustTarget)
    }

    builds.android {
        // The APK carries the Rust library itself, in `jniLibs`. Nothing is downloaded or
        // loaded from outside the package.
        embedRustLibrary = true
    }
}

// ── Android ─────────────────────────────────────────────────────────────────────
//
// The device this targets is a 64-bit ARM Android tablet; other ABIs would double the
// Rust build time for binaries nobody runs, and are added when there is a reason.
//
// A release build has to be signed before Android will install it, and a release build is the
// only one worth putting on a device to judge how the application behaves: Android refuses to
// optimize a debuggable package, so a debug build is several times slower by construction and
// says nothing about the application a user gets. The keystore is read from a
// `keystore.properties` beside this repository's root that is not committed; without that file
// the debug keystore is used, which installs and runs but is not an identity to release under.
val signingProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}
if (signingProperties.isEmpty && gradle.startParameter.taskNames.any { it.contains("Release") }) {
    logger.warn(
        "keystore.properties not found: the release build will be signed with the debug keystore",
    )
}
val debugKeystore = File(System.getProperty("user.home"), ".android/debug.keystore")

android {
    namespace = "dev.qualia.qualia2fa"
    // 35 rather than 36: the Kotlin plugin this build is pinned to (2.1.21) tops out at
    // AGP 8.7, and AGP 8.7 tops out at compileSdk 35. The device runs a newer Android,
    // which runs an application built against an older SDK without complaint.
    compileSdk = 35

    // r28 and newer produce libraries whose load segments are aligned for 16 KB pages,
    // which Android 15 and later may use. `llvm-readelf -l` on the built library shows it:
    // every LOAD segment must say `Align 0x4000`.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "dev.qualia.qualia2fa"
        // `java.nio.file` for the vault and the master key, `java.util.Base64` for the text
        // encoding of a sync code: both are API 26.
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.0.2"

        ndk.abiFilters += setOf("arm64-v8a")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("release") {
            storeFile = signingProperties.getProperty("storeFile")?.let { File(it) } ?: debugKeystore
            storePassword = signingProperties.getProperty("storePassword") ?: "android"
            keyAlias = signingProperties.getProperty("keyAlias") ?: "androiddebugkey"
            keyPassword = signingProperties.getProperty("keyPassword") ?: "android"
        }
    }

    buildTypes {
        // Signed so that `assembleRelease` produces something that can be installed: the release
        // variant exists here to be put on a device, because it is the only one whose performance
        // means anything.
        //
        // R8 is left off. This application reaches its Rust library through JNA and through
        // generated UniFFI bindings, neither of which has been exercised through a shrinking
        // build, and the release build is fast enough without it.
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }

    packaging {
        // The APK strips native symbols by default, which makes a crash inside the Rust
        // library a list of addresses. Keeping them is worth the size while the platform is
        // being brought up.
        jniLibs.keepDebugSymbols += "**/*.so"
    }
}

uniffi {
    generateFromLibrary {
        // The UniFFI namespace is the Rust crate name with underscores.
        namespace = "qualia2fa_ffi"
        packageName = "dev.qualia.qualia2fa.ffi"
    }
}

compose.resources {
    // The generated accessors are used from the user interface and from the tests, and
    // the tests of a multiplatform module are a different compilation unit.
    publicResClass = true
    packageOfResClass = "dev.qualia.qualia2fa.resources"
}

compose.desktop {
    application {
        mainClass = "dev.qualia.qualia2fa.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Qualia2FA"
            packageVersion = "0.0.2"

            // jpackage turns this into the page the installer shows before it copies anything.
            // The packages are x64 and the application requires 64-bit Windows, and nothing in
            // the installer said so: `Template` in the MSI is `x64;1033`, which a user never sees.
            licenseFile.set(layout.projectDirectory.file("../packaging/install-notice.txt"))
            description = "Serverless, non-realtime two-factor authentication"
            vendor = "Qualia2FA"

            windows {
                // The mark the installed application, its shortcuts and the installer carry.
                // Committed as an artifact and regenerated by hand with `icons/make_ico.py`
                // when the drawing changes; the script's own header has the render command.
                iconFile.set(project.file("icons/qualia2fa.ico"))
            }
        }
    }
}
