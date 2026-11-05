import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm") version "2.3.10"
    kotlin("plugin.serialization") version "2.3.10"
    kotlin("plugin.compose") version "2.3.10"
    id("org.jetbrains.compose") version "1.9.3"
}

// CI numbers each build (1.1.<run>), so every installer is newer than the last and upgrades it.
val appVersion = System.getenv("CONVX_VERSION")?.takeIf { it.isNotBlank() } ?: "1.0.0"
val appCommit = System.getenv("GITHUB_SHA")?.takeIf { it.isNotBlank() } ?: "dev"

group = "com.convx.desktop"
version = appVersion

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.addAll(
            "androidx.compose.foundation.ExperimentalFoundationApi",
            "androidx.compose.ui.ExperimentalComposeUiApi",
            "androidx.compose.material3.ExperimentalMaterial3Api",
            "androidx.compose.foundation.layout.ExperimentalLayoutApi",
            "kotlinx.serialization.ExperimentalSerializationApi",
        )
    }
    sourceSets["main"].kotlin.srcDirs(
        "src/main/kotlin",
        // Stand-ins for the handful of Android/Timber types the shared modules name.
        "src/shims/kotlin",
        // Shared with the phone app, compiled as-is.
        "../innertube/src/main/kotlin",
        "../lyrics/src/main/kotlin",
        "../canvas/src/main/kotlin",
        // Only the phone's Shazam client is copied there.
        "../integrations/src/main/kotlin",
    )
}

val ktor = "3.4.0"
val javacpp = "1.5.12"
val ffmpeg = "7.1.1-$javacpp"

// FFmpeg ships one native jar per OS; bundle Windows for the installer and the
// build machine's own OS so `run` works wherever it is launched.
val hostNatives = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val aarch = arch.contains("aarch64") || arch.contains("arm64")
    when {
        os.contains("win") -> "windows-x86_64"
        os.contains("mac") -> if (aarch) "macosx-arm64" else "macosx-x86_64"
        else -> if (aarch) "linux-arm64" else "linux-x86_64"
    }
}
val nativeClassifiers = setOf("windows-x86_64", hostNatives)

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.desktop.windows_x64)
    implementation(compose.material3)
    implementation(compose.foundation)
    implementation(compose.animation)

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    // innertube
    implementation("io.ktor:ktor-client-core:$ktor")
    implementation("io.ktor:ktor-client-okhttp:$ktor")
    implementation("io.ktor:ktor-client-content-negotiation:$ktor")
    implementation("io.ktor:ktor-serialization-kotlinx-json:$ktor")
    implementation("io.ktor:ktor-client-encoding:$ktor")
    implementation("org.brotli:dec:0.1.2")
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.25.2")

    // lyrics
    implementation("org.jsoup:jsoup:1.22.1")

    // Audio: FFmpeg decodes every format YouTube serves (Opus/WebM, AAC/MP4).
    implementation("org.bytedeco:javacv:$javacpp") { isTransitive = false }
    implementation("org.bytedeco:javacpp:$javacpp")
    implementation("org.bytedeco:ffmpeg:$ffmpeg")
    nativeClassifiers.forEach { classifier ->
        implementation("org.bytedeco:javacpp:$javacpp:$classifier")
        implementation("org.bytedeco:ffmpeg:$ffmpeg:$classifier")
    }

    // Native window tweaks on Windows (dark title bar).
    implementation("net.java.dev.jna:jna-platform:5.17.0")
    // The JetBrains Runtime's custom title bar, so the top bar can be the window's title bar.
    implementation("org.jetbrains.runtime:jbr-api:1.12.0")
    // Reads the phone's song.db out of a phone backup.
    implementation("org.xerial:sqlite-jdbc:3.49.1.0")
    // Lyrics romanization, as on the phone: Japanese readings, and pinyin from the phone's own jars
    // (JitPack serves an empty jar for tinypinyin).
    implementation("com.atilika.kuromoji:kuromoji-ipadic:0.9.0")
    implementation(files("libs/tinypinyin-2.0.3.jar", "libs/ahocorasick-0.3.0.jar"))
    // Android's org.json, for the phone's AI translation clients (com.convx.music.api).
    implementation("org.json:json:20250517")

    testImplementation(kotlin("test"))
}

// The version and commit the app reads at run time (AppInfo), for About and the updater.
val buildInfo = tasks.register("buildInfo") {
    val out = layout.buildDirectory.dir("generated/build-info")
    val text = "version=$appVersion\ncommit=$appCommit\n"
    inputs.property("text", text)
    outputs.dir(out)
    doLast { out.get().file("convx-build.properties").asFile.apply { parentFile.mkdirs() }.writeText(text) }
}
sourceSets["main"].resources.srcDir(buildInfo)

compose.desktop {
    application {
        mainClass = "com.convx.desktop.MainKt"
        jvmArgs += listOf("-Xmx1g", "-Dfile.encoding=UTF-8")
        // The installer bundles this Java. CI points it at the JetBrains Runtime, whose custom
        // title bar the app uses; without it the app keeps the Windows title bar.
        System.getenv("CONVX_JBR")?.takeIf { it.isNotBlank() }?.let { javaHome = it }

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "Convx"
            packageVersion = appVersion
            description = "Convx music player for desktop"
            vendor = "Convx"
            copyright = "GPL-3.0"
            licenseFile.set(project.file("../LICENSE"))
            // NewPipe's Rhino, FFmpeg's loader and TLS all reach for odd JDK modules; ship them all.
            includeAllModules = true

            windows {
                iconFile.set(project.file("src/main/resources/convx.ico"))
                menuGroup = "Convx"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                // Fixed so new installers upgrade the existing install in place.
                upgradeUuid = "6f1d1a6e-6a52-4f6b-9b0e-3c1f2d7a9c41"
            }
        }
    }
}

// Renders the main screens to build/snapshots with live data, to check the look on CI.
tasks.register<JavaExec>("snapshots") {
    group = "verification"
    description = "Renders the main screens to PNG files in build/snapshots."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.convx.desktop.tools.SnapshotsKt")
    args(layout.buildDirectory.dir("snapshots").get().asFile.absolutePath)
    jvmArgs("-Xmx2g", "-Dfile.encoding=UTF-8")
}

// Resolves, downloads and decodes a few songs without a sound card, to check playback on CI.
tasks.register<JavaExec>("playbackCheck") {
    group = "verification"
    description = "Plays a few songs through the stream resolver, cache and decoder."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.convx.desktop.tools.PlaybackCheckKt")
    jvmArgs("-Xmx1g", "-Dfile.encoding=UTF-8")
}
