import dev.nucleusframework.desktop.application.dsl.CompressionLevel
import dev.nucleusframework.desktop.application.dsl.GraalvmDistribution
import dev.nucleusframework.desktop.application.dsl.NativeImageOptimization
import dev.nucleusframework.desktop.application.dsl.TargetFormat
import org.gradle.jvm.toolchain.JvmVendorSpec
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.nucleus)
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_25) }
}

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
}

dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation(libs.nucleus.application)
    implementation(libs.nucleus.decorated.window.tao)
    implementation(libs.nucleus.decorated.window.material3)
}

val releaseVersion =
    System.getenv("RELEASE_VERSION")
        ?.removePrefix("v")
        ?.takeIf { it.isNotBlank() && it.first().isDigit() }
        ?: "1.0.0"

val nativePackageVersion = releaseVersion.substringBefore("-")

nucleus.application {
    mainClass = "MainKt"
    // Nucleus `run` forks `javaHome`, not the Java plugin toolchain. Point it at JDK 25
    // so a JBR 21 Gradle daemon (typical from IDEA) does not launch class-file 69 bytecode.
    javaHome =
        javaToolchains
            .launcherFor(java.toolchain)
            .get()
            .metadata.installationPath.asFile.absolutePath

    graalvm {
        isEnabled = true
        javaLanguageVersion = 25
        jvmVendor = JvmVendorSpec.ORACLE
        imageName = "MusicRadio"
        // -O3 and PGO only exist on Oracle GraalVM. Community would silently stay on -O2.
        toolchain {
            distribution = GraalvmDistribution.ORACLE
        }
        optimization = NativeImageOptimization.LEVEL_3
    }

    nativeDistributions {
        targetFormats(TargetFormat.Dmg, TargetFormat.Zip, TargetFormat.Nsis, TargetFormat.Deb)
        packageName = "Music Radio"
        packageVersion = releaseVersion
        vendor = "abaye"
        cleanupNativeLibs = true
        compressionLevel = CompressionLevel.Ultra
        // electron-builder refuses to build a .deb without it: "Please specify project homepage".
        homepage = "https://github.com/abaye123/MusicRadio"

        linux {
            iconFile.set(project.file("appIcons/LinuxIcon.png"))
            debPackageVersion = releaseVersion
            // Likewise mandatory for .deb; the address only has to be well-formed.
            debMaintainer = "abaye <abaye123@users.noreply.github.com>"
        }
        windows {
            iconFile.set(project.file("appIcons/WindowsIcon.ico"))
            packageVersion = nativePackageVersion
            upgradeUuid = "3f1c9a52-84d7-4e63-9b0a-6c2f7d18e405"
        }
        macOS {
            packageVersion = nativePackageVersion
            bundleID = "co.abaye.musicradio"
        }
    }
}
