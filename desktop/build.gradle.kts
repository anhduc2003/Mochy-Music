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

    // audio decoding beyond what the JDK ships (WAV/AIFF/AU). Each of these registers a
    // javax.sound.sampled ServiceProvider, so AudioSystem picks them up by itself and the player
    // needs no format-specific code: mp3spi adds MP3, vorbisspi adds OGG/Opus, and both pull in
    // tritonus-share, which is the base they register against.
    implementation("com.googlecode.soundlibs:mp3spi:1.9.5.4")
    implementation("com.googlecode.soundlibs:vorbisspi:1.0.3.3")
    implementation("com.googlecode.soundlibs:tritonus-share:0.3.7.4")

    // FLAC and AAC/M4A as javax.sound SPI providers too, so every format this app lists goes
    // through one path: AudioSystem finds the provider, the player decodes nothing itself.
    implementation("com.tianscar.javasound:javasound-flac:1.4.1")

    // library persistence. Room is Android-only; the PC build talks SQLite directly through JDBC.
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")

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
