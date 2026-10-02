import java.io.File

plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
    alias(libs.plugins.shadow)
}

group = "com.github.ahatem"
version = System.getenv("APP_VERSION") ?: "dev"

application {
    mainClass.set("com.github.ahatem.qtranslate.app.MainKt")
}

repositories {
    mavenCentral()
    google()
    maven("https://s01.oss.sonatype.org/content/repositories/snapshots")
    maven("https://central.sonatype.com/repository/maven-snapshots/")
    maven("https://jitpack.io")
}

dependencies {
    // Modules
    implementation(project(":api"))
    implementation(project(":core"))
    implementation(project(":ui-swing"))

    // Coroutines — needed for runBlocking in Main.kt and AppScope
    implementation(libs.kotlinxCoroutines)

    // Serialization — used in buildDependencies for the shared Json instance
    implementation(libs.kotlinxSerialization)

    // Ktor — shared HttpClient created here and injected into Updater
    implementation(libs.bundles.ktor)

    // JLayer — MP3 decoding library for JLayerAudioPlayer
    implementation(libs.jlayer)

    // FlatLaf — referenced directly in AppUiSetup (FlatLaf, FontUtils).
    // Also a transitive dep via :ui-swing, but needed here for compilation.
    implementation(libs.bundles.flatlaf)

    // Logging — SLF4J API + Logback backend
    // SLF4J is the facade; Logback does the actual writing.
    // The :api module's Logger interface bridges to SLF4J here in :app.
    implementation(libs.slf4j.api)
    implementation(libs.logback.classic)

    // Tests
    testImplementation(kotlin("test"))
}

tasks.shadowJar {
    // Output: QTranslate.jar — the name users will see and double-click
    archiveBaseName.set("QTranslate")
    archiveClassifier.set("")
    archiveVersion.set("")

    manifest {
        attributes["Main-Class"] = "com.github.ahatem.qtranslate.app.MainKt"
    }

    // Exclude duplicate META-INF files that Shadow picks up from dependencies
    mergeServiceFiles()
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

// A portable Windows app image: native launcher plus a bundled Java 21 runtime.
val windowsInput = tasks.register<Sync>("windowsInput") {
    dependsOn(tasks.shadowJar)
    from(tasks.shadowJar.flatMap { it.archiveFile })
    into(layout.buildDirectory.dir("windows-input"))
}

val packagingJava = javaToolchains.launcherFor {
    languageVersion.set(JavaLanguageVersion.of(21))
}

val windowsImage = tasks.register<Exec>("windowsImage") {
    group = "distribution"
    description = "Build a portable Windows x64 application with Java 21 included"
    dependsOn(windowsInput, ":plugins:google-services:shadowJar", ":plugins:bing-services:shadowJar", ":plugins:ai-services:shadowJar")
    notCompatibleWithConfigurationCache("jpackage and bundled resource assembly use the project filesystem")
    doFirst {
        check(System.getProperty("os.name").startsWith("Windows")) { "windowsImage requires Windows" }
        val destination = layout.buildDirectory.dir("windows").get().asFile
        // Only remove this task's generated image, within this project's build directory.
        val previous = destination.resolve("QTranslate")
        check(previous.canonicalPath.startsWith(layout.buildDirectory.get().asFile.canonicalPath + File.separator))
        if (previous.exists()) {
            // jpackage marks its launcher read-only on Windows.
            previous.walkBottomUp().forEach { it.setWritable(true) }
            check(previous.deleteRecursively()) { "Close the generated application before rebuilding" }
        }
        commandLine(
            packagingJava.get().metadata.installationPath.file("bin/jpackage.exe").asFile,
            "--type", "app-image", "--name", "QTranslate",
            "--app-version", "1.2.1", "--vendor", "QTranslate",
            "--input", layout.buildDirectory.dir("windows-input").get().asFile,
            "--main-jar", "QTranslate.jar", "--main-class", application.mainClass.get(),
            "--dest", destination,
            "--java-options", "-Dfile.encoding=UTF-8",
            "--jlink-options", "--strip-debug --no-man-pages --no-header-files --compress=2",
            "--add-modules", "java.base,java.desktop,java.logging,java.naming,java.net.http,java.sql,java.xml,jdk.crypto.ec,jdk.unsupported,jdk.accessibility"
        )
    }
    doLast {
        val image = layout.buildDirectory.dir("windows/QTranslate").get().asFile
        copy {
            from(rootProject.file("languages"))
            into(image.resolve("languages"))
        }
        copy {
            from(rootProject.file("themes"))
            into(image.resolve("themes"))
        }
        copy {
            from(rootProject.file("plugins/google-services/build/libs/google-services-plugin.jar"))
            from(rootProject.file("plugins/bing-services/build/libs/bing-services-plugin.jar"))
            from(rootProject.file("plugins/ai-services/build/libs/ai-services-plugin.jar"))
            into(image.resolve("plugins"))
        }
        copy {
            from(rootProject.file("LICENSE"))
            into(image)
        }
    }
}

tasks.register<Zip>("windowsZip") {
    group = "distribution"
    dependsOn(windowsImage)
    from(layout.buildDirectory.dir("windows"))
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveFileName.set("QTranslate-1.2.1-windows-x64.zip")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.register<Jar>("artifactSmokeJar") {
    group = "verification"
    dependsOn(tasks.testClasses)
    from(sourceSets.test.get().output)
    archiveFileName.set("artifact-smoke.jar")
    destinationDirectory.set(layout.buildDirectory.dir("validation"))
}
