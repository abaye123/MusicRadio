import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.metro)
    alias(libs.plugins.structured.coroutines)
    alias(libs.plugins.stability.analyzer)
    alias(libs.plugins.aboutLibraries)
}

kotlin {
    android {
        namespace = "dev.kdroid.musicradio"
        // 37 is a floor, not a preference: materialKolor, aboutlibraries and the AndroidX
        // Compose artifacts all publish AAR metadata demanding it.
        compileSdk = 37
        minSdk = 26
        androidResources.enable = true
        compilerOptions { jvmTarget = JvmTarget.JVM_17 }
    }

    jvm {
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.compose.runtime)
            api(libs.compose.ui)
            api(libs.compose.foundation)
            api(libs.compose.resources)
            api(libs.compose.ui.tooling.preview)
            api(libs.compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.structured.coroutines.annotations)
            implementation(libs.androidx.lifecycle.viewmodel)
            implementation(libs.androidx.lifecycle.runtime)
            implementation(libs.androidx.lifecycle.viewmodel.navigation3)
            implementation(libs.compose.nav3)
            implementation(libs.kotlinx.datetime)
            implementation(libs.materialKolor)
            implementation(libs.aboutlibraries.compose.m3)
            implementation(libs.ktor.client.core)
            api(libs.composemediaplayer.audio)
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.compose.ui.test)
            implementation(libs.kotlinx.coroutines.test)
        }

        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.android)
            implementation(libs.androidx.activityCompose)
            implementation(libs.ktor.client.okhttp)
        }

        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.nucleus.core.runtime)
            implementation(libs.nucleus.application)
            implementation(libs.nucleus.decorated.window.tao)
            implementation(libs.nucleus.system.color)
            implementation(libs.nucleus.media.control)
            implementation(libs.nucleus.updater.runtime)
        }
    }
}

dependencies {
    androidRuntimeClasspath(libs.compose.ui.tooling)
}

structuredCoroutines {
    useKmpCommonProfile()
}

compose.resources {
    // Independent of rootProject.name - renaming the app must not move Res.
    packageOfResClass = "musicradio.shared.generated.resources"
}

val stabilityConfig = rootProject.layout.projectDirectory.file("config/stability-config.conf")

composeCompiler {
    stabilityConfigurationFiles.add(stabilityConfig)
}

aboutLibraries {
    export {
        outputFile = file("src/commonMain/composeResources/files/aboutlibraries.json")
    }
    library {
        duplicationMode = com.mikepenz.aboutlibraries.plugin.DuplicateMode.MERGE
    }
}

tasks.matching {
    it.name.startsWith("generateResourceAccessorsForCommonMain") ||
        it.name.startsWith("copyNonXmlValueResourcesForCommonMain") ||
        it.name.startsWith("prepareComposeResourcesTaskForCommonMain")
}.configureEach {
    dependsOn("exportLibraryDefinitions")
}

composeStabilityAnalyzer {
    stabilityConfigurationFiles.add(stabilityConfig)
    traceAll {
        enabled.set(true)
        threshold.set(2)
        variants.set(listOf("debug"))
    }
    stabilityValidation {
        enabled.set(true)
        outputDir.set(layout.projectDirectory.dir("stability"))
        includeTests.set(false)
        // No baseline is checked in yet: the first run writes one instead of failing.
        failOnStabilityChange.set(false)
    }
}
