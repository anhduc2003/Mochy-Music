import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("plugin.serialization")
}

/**
 * The PC build of Mochy.
 *
 * A separate Gradle module rather than a target on `:app`, because `:app` is an Android
 * application: its whole UI (149k of the 221k lines) is written against Compose for Android,
 * ExoPlayer, Room, Hilt and the Android Context. None of that compiles off Android. What *is*
 * shared is the part that never needed Android in the first place — the music analysis, the
 * crossmix planner, the lyrics parsers — and that comes over as source, one package at a time.
 */
kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.components.resources)

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // audio: tag reading. Coordinates are net.jthink — org.jaudiotagger is the old, abandoned group
    implementation("net.jthink:jaudiotagger:3.0.1")

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}

compose.desktop {
    application {
        mainClass = "com.mochyy.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Mochy"
            packageVersion = "1.0.0"
            description = "Mochy Music for PC"

            windows {
                menuGroup = "Mochy"
                // a stable UUID so upgrades replace the previous install instead of stacking
                upgradeUuid = "6f3b1d5a-2c47-4e88-9a1e-7d5c3b8f2a61"
            }
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnit()
    testLogging {
        events("passed", "failed", "skipped")
        showStandardStreams = true
    }
}
