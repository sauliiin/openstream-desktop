import java.util.Properties
import javax.xml.parsers.DocumentBuilderFactory
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.w3c.dom.Element

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

val appVersion = "2.0.0"

kotlin {
    jvmToolchain(21)
    compilerOptions {
        optIn.addAll(
            "kotlinx.coroutines.ExperimentalCoroutinesApi",
            "androidx.compose.ui.ExperimentalComposeUiApi",
        )
        freeCompilerArgs.addAll("-Xexpect-actual-classes")
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

ksp {
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.foundation)
    implementation(compose.ui)
    implementation(compose.animation)
    implementation(compose.runtime)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.navigation.compose)

    implementation(libs.room.runtime)
    implementation(libs.sqlite.bundled)
    ksp(libs.room.compiler)
    implementation(libs.datastore.preferences.core)

    implementation(libs.coroutines.core)
    implementation(libs.coroutines.swing)
    implementation(libs.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    implementation(libs.jna)
    implementation(libs.jna.platform)
    implementation(libs.libtorrent4j)
    // Only the native half for the OS being packaged: each one is ~10 MB.
    if (System.getProperty("os.name").lowercase().contains("win")) {
        runtimeOnly(libs.libtorrent4j.windows)
    } else {
        runtimeOnly(libs.libtorrent4j.linux)
    }

    testImplementation(libs.junit)
    testImplementation(kotlin("test"))
}

// ---------------------------------------------------------------------------
// BuildConfig: the same API keys the Android TV app reads from local.properties.
// Looked up in Gradle properties, then environment variables, then
// local.properties at the repository root (never committed).
// ---------------------------------------------------------------------------
val localSecrets = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.isFile) file.inputStream().use(::load)
}

fun secret(name: String): String =
    providers.gradleProperty(name).orNull
        ?: providers.environmentVariable(name).orNull
        ?: localSecrets.getProperty(name).orEmpty()

fun quoted(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("$", "\\$") + "\""

val secretNames = listOf(
    "TMDB_API_KEY", "OMDB_API_KEY", "FANART_TV_API_KEY", "TRAKT_CLIENT_ID",
    "TRAKT_CLIENT_SECRET", "SIMKL_CLIENT_ID", "OPENSUBTITLES_API_KEY", "WYZIE_API_KEY",
    "FIREBASE_WEB_API_KEY", "GOOGLE_WEB_CLIENT_ID",
)

val generatedSrc = layout.buildDirectory.dir("generated/omnistream/kotlin")
val generatedRes = layout.buildDirectory.dir("generated/omnistream/resources")

val generateBuildConfig by tasks.registering {
    val out = generatedSrc
    val values = secretNames.associateWith { secret(it) }
    inputs.property("secrets", values.mapValues { it.value.hashCode() })
    inputs.property("version", appVersion)
    outputs.dir(out)
    doLast {
        val file = out.get().file("com/mdblisthub/tv/BuildConfig.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(buildString {
            appendLine("package com.mdblisthub.tv")
            appendLine()
            appendLine("object BuildConfig {")
            appendLine("    const val VERSION_NAME = ${quoted(appVersion)}")
            appendLine("    const val DEBUG = false")
            values.forEach { (k, v) -> appendLine("    const val $k = ${quoted(v)}") }
            appendLine("}")
        })
    }
}

// ---------------------------------------------------------------------------
// Android resources -> desktop. The TV app's `res/` folders are kept as they
// are (so a sync from upstream is a plain copy); this turns them into:
//   * an `R` object per package, with the same names the code already uses;
//   * the files themselves on the classpath under /android-res/, read by
//     `com.mdblisthub.desktop.res.AndroidResources` at runtime.
// ---------------------------------------------------------------------------
val resModules = mapOf(
    "app" to "com.mdblisthub.tv",
    "core-ui" to "com.mdblisthub.tv.core.ui",
    "desktop" to "com.mdblisthub.desktop",
)

val generateAndroidResources by tasks.registering {
    val resRoot = layout.projectDirectory.dir("res")
    val srcOut = generatedSrc
    val resOut = generatedRes
    inputs.dir(resRoot)
    outputs.dirs(srcOut, resOut)
    doLast {
        val root = resRoot.asFile
        val types = sortedMapOf<String, MutableSet<String>>()
        fun add(type: String, name: String) = types.getOrPut(type) { sortedSetOf() }.add(name)

        resModules.keys.forEach { module ->
            val moduleDir = File(root, module)
            moduleDir.listFiles()?.forEach { dir ->
                val folder = dir.name
                when {
                    folder == "values" -> dir.listFiles()?.filter { it.extension == "xml" }?.forEach { xml ->
                        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml)
                        val children = doc.documentElement.childNodes
                        for (i in 0 until children.length) {
                            val node = children.item(i) as? Element ?: continue
                            val name = node.getAttribute("name").takeIf { it.isNotBlank() } ?: continue
                            when (node.tagName) {
                                "string" -> add("string", name)
                                "plurals" -> add("plurals", name)
                                "string-array" -> add("array", name)
                                "color" -> add("color", name)
                            }
                        }
                    }
                    folder.startsWith("drawable") -> dir.listFiles()?.forEach { add("drawable", it.nameWithoutExtension) }
                    folder == "font" -> dir.listFiles()?.forEach { add("font", it.nameWithoutExtension) }
                    folder == "raw" -> dir.listFiles()?.forEach { add("raw", it.nameWithoutExtension) }
                }
            }
        }

        // One id space shared by every package's R, like a merged Android app.
        var nextId = 0x7f000001
        val ids = types.mapValues { (_, names) -> names.associateWith { nextId++ } }

        resModules.values.forEach { pkg ->
            val file = srcOut.get().file(pkg.replace('.', '/') + "/R.kt").asFile
            file.parentFile.mkdirs()
            file.writeText(buildString {
                appendLine("package $pkg")
                appendLine()
                appendLine("@Suppress(\"ClassName\", \"unused\")")
                appendLine("object R {")
                ids.forEach { (type, names) ->
                    appendLine("    object $type {")
                    names.forEach { (name, id) -> appendLine("        const val $name: Int = $id") }
                    appendLine("    }")
                }
                appendLine("}")
            })
        }

        // id -> "type/name" table, read by AndroidResources at startup.
        val table = resOut.get().file("android-res/ids.txt").asFile
        table.parentFile.mkdirs()
        table.writeText(buildString {
            ids.forEach { (type, names) -> names.forEach { (name, id) -> appendLine("$id $type/$name") } }
        })
        resModules.keys.forEach { module ->
            File(root, module).copyRecursively(resOut.get().dir("android-res/$module").asFile, overwrite = true)
        }
        resOut.get().file("android-res/modules.txt").asFile.writeText(resModules.keys.joinToString("\n"))
    }
}

sourceSets {
    main {
        kotlin.srcDir(generatedSrc)
        resources.srcDir(generatedRes)
    }
}

tasks.named("compileKotlin") { dependsOn(generateBuildConfig, generateAndroidResources) }
tasks.named("processResources") { dependsOn(generateAndroidResources) }
tasks.matching { it.name.startsWith("ksp") }.configureEach { dependsOn(generateBuildConfig, generateAndroidResources) }

compose.desktop {
    application {
        mainClass = "com.mdblisthub.desktop.MainKt"
        jvmArgs += listOf(
            "-Xms256m",
            "-XX:+UseZGC",
            "-Dsun.java2d.uiScale.enabled=true",
            "-Dawt.useSystemAAFontSettings=on",
            "--enable-native-access=ALL-UNNAMED",
            // Lets Main set the X11 WM_CLASS, which docks and taskbars group by.
            "--add-opens=java.desktop/sun.awt.X11=ALL-UNNAMED",
        )

        nativeDistributions {
            targetFormats(TargetFormat.AppImage, TargetFormat.Deb, TargetFormat.Exe, TargetFormat.Msi)
            packageName = "OmniStream"
            packageVersion = appVersion
            description = "OmniStream — cliente de mídia para as suas listas"
            copyright = "Copyright © Saulo Hugo Rossi"
            vendor = "Saulo Hugo Rossi"
            licenseFile.set(rootProject.file("LICENSE"))
            // Files shipped next to the app, per OS (packaging/resources/<os>/).
            // CI drops libmpv-2.dll into windows/; on Linux libmpv comes from
            // the system, see scripts/build-appimage.sh.
            appResourcesRootDir.set(project.layout.projectDirectory.dir("packaging/resources"))
            // From `suggestRuntimeModules`, plus what reflection-free analysis
            // cannot see: TLS curves, every locale's date/number formats
            // (pt-BR, es, fr) and the legacy charsets subtitles arrive in.
            modules(
                "java.instrument", "jdk.httpserver", "jdk.unsupported", "java.naming",
                "jdk.crypto.ec", "jdk.localedata", "jdk.charsets", "java.management",
            )

            linux {
                iconFile.set(project.file("packaging/icon.png"))
                packageName = "omnistream"
                appCategory = "AudioVideo"
                menuGroup = "AudioVideo"
                shortcut = true
            }
            windows {
                iconFile.set(project.file("packaging/icon.ico"))
                menuGroup = "OmniStream"
                shortcut = true
                dirChooser = true
                perUserInstall = true
                upgradeUuid = "6c0c8f39-4b7e-4f5e-9d0e-3b8b8a5c2f11"
            }
        }

        buildTypes.release.proguard {
            isEnabled.set(false)
        }
    }
}

// Offscreen snapshots for development: ./gradlew :desktop:snapshot -Pshots="3000:shot:a 5000:key:DirectionDown 6000:shot:b"
tasks.register<JavaExec>("snapshot") {
    group = "verification"
    mainClass.set("com.mdblisthub.desktop.dev.SnapshotKt")
    classpath = sourceSets.main.get().runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED", "-Djava.awt.headless=true")
    val outDir = providers.gradleProperty("shotsDir").orElse(layout.buildDirectory.dir("snapshots").get().asFile.path)
    args(listOf(outDir.get()) + providers.gradleProperty("shots").orElse("4000:shot:start").get().split(" "))
}

// Drives the real PlaybackController through a cascade of URLs: ./gradlew :desktop:playerProbe -PprobeArgs="out.png url1 url2"
tasks.register<JavaExec>("playerProbe") {
    group = "verification"
    mainClass.set("com.mdblisthub.desktop.dev.PlayerProbeKt")
    classpath = sourceSets.main.get().runtimeClasspath
    jvmArgs("--enable-native-access=ALL-UNNAMED")
    args(providers.gradleProperty("probeArgs").getOrElse("probe.png").split(" "))
}
