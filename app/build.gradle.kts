import java.io.File
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.TaskAction

plugins {
    id("buildsrc.convention.kotlin-jvm")
    application
    alias(libs.plugins.shadow)
}

val appVersion = providers.gradleProperty("appVersion").get()

group = "org.shariftranslate"
version = appVersion

application {
    mainClass.set("org.shariftranslate.app.MainKt")
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
    // Output: SharifTranslate.jar — the name users will see and double-click
    archiveBaseName.set("SharifTranslate")
    archiveClassifier.set("")
    archiveVersion.set("")

    manifest {
        attributes["Main-Class"] = "org.shariftranslate.app.MainKt"
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

// Refresh an existing portable installation without deleting its user data.
tasks.register("refreshWindowsApp") {
    group = "distribution"
    description = "Update application and bundled plugin JARs in an existing Windows image (close the app first)"
    dependsOn(tasks.shadowJar)
    dependsOn(":plugins:google-services:shadowJar", ":plugins:bing-services:shadowJar", ":plugins:ai-services:shadowJar")
    val sourceJar = tasks.shadowJar.flatMap { it.archiveFile }
    val targetJar = layout.buildDirectory.file("windows/Sharif Translate/app/SharifTranslate.jar")
    val launcher = layout.buildDirectory.file("windows/Sharif Translate/Sharif Translate.exe")
    doLast {
        check(launcher.get().asFile.isFile) {
            "Create the portable installation first with :app:windowsImage"
        }
        sourceJar.get().asFile.copyTo(targetJar.get().asFile, overwrite = true)
        val pluginDirectory = launcher.get().asFile.parentFile.resolve("plugins").also { it.mkdirs() }
        for (module in listOf("google-services", "bing-services", "ai-services")) {
            rootProject.file("plugins/$module/build/libs/$module-plugin.jar")
                .copyTo(pluginDirectory.resolve("$module-plugin.jar"), overwrite = true)
        }
        for (name in listOf("NOTICE.md", "CHANGES.md")) {
            rootProject.file("plugins/$name").copyTo(pluginDirectory.resolve(name), overwrite = true)
        }
        for (name in listOf("LICENSE", "README.md")) {
            rootProject.file(name).copyTo(launcher.get().asFile.parentFile.resolve(name), overwrite = true)
        }

    }
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
        val previous = destination.resolve("Sharif Translate")
        check(previous.canonicalPath.startsWith(layout.buildDirectory.get().asFile.canonicalPath + File.separator))
        if (previous.exists()) {
            // jpackage marks its launcher read-only on Windows.
            previous.walkBottomUp().forEach { it.setWritable(true) }
            check(previous.deleteRecursively()) { "Close the generated application before rebuilding" }
        }
        commandLine(
            packagingJava.get().metadata.installationPath.file("bin/jpackage.exe").asFile,
            "--type", "app-image", "--name", "Sharif Translate",
            "--app-version", appVersion, "--vendor", "Ali Esmaeili",
            "--input", layout.buildDirectory.dir("windows-input").get().asFile,
            "--main-jar", "SharifTranslate.jar", "--main-class", application.mainClass.get(),
            "--dest", destination,
            "--icon", rootProject.file("ui-swing/src/main/resources/icons/app/icon.ico"),
            "--java-options", "-Dfile.encoding=UTF-8",
            "--jlink-options", "--strip-debug --no-man-pages --no-header-files --compress=2",
            "--add-modules", "java.base,java.desktop,java.logging,java.naming,java.net.http,java.sql,java.xml,jdk.crypto.ec,jdk.unsupported,jdk.accessibility"
        )
    }
    doLast {
        val image = layout.buildDirectory.dir("windows/Sharif Translate").get().asFile
        copy {
            from(rootProject.file("languages"))
            into(image.resolve("languages"))
        }
        copy {
            from(rootProject.file("wiki"))
            into(image.resolve("wiki"))
        }
        copy {
            from(rootProject.file("themes"))
            into(image.resolve("themes"))
        }
        copy {
            from(rootProject.file("plugins/google-services/build/libs/google-services-plugin.jar"))
            from(rootProject.file("plugins/bing-services/build/libs/bing-services-plugin.jar"))
            from(rootProject.file("plugins/ai-services/build/libs/ai-services-plugin.jar"))
            from(rootProject.file("plugins/NOTICE.md"))
            from(rootProject.file("plugins/CHANGES.md"))
            into(image.resolve("plugins"))
        }
        copy {
            from(rootProject.file("LICENSE"))
            from(rootProject.file("README.md"))
            into(image)
        }
    }
}

tasks.register<Zip>("windowsZip") {
    group = "distribution"
    dependsOn(windowsImage)
    from(layout.buildDirectory.dir("windows/Sharif Translate")) {
        into("Sharif Translate")
    }
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    archiveFileName.set("SharifTranslate-${appVersion}-windows-x64.zip")
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

// Prevent the UI version from drifting away from Gradle and the Windows package.
abstract class VerifyAppVersion : DefaultTask() {
    @get:Input abstract val expectedVersion: Property<String>
    @get:InputFile abstract val constantsFile: RegularFileProperty

    @TaskAction fun verify() {
        val version = expectedVersion.get()
        check(constantsFile.get().asFile.readText().contains("const val APP_VERSION = \"$version\"")) {
            "AppConstants.APP_VERSION must equal appVersion=$version in gradle.properties"
        }
    }
}

tasks.register<VerifyAppVersion>("verifyAppVersion") {
    group = "verification"
    expectedVersion.set(appVersion)
    constantsFile.set(rootProject.layout.projectDirectory.file("core/src/main/kotlin/org/shariftranslate/core/shared/AppConstants.kt"))
}
tasks.named("check") { dependsOn("verifyAppVersion") }
tasks.named("windowsImage") { dependsOn("verifyAppVersion") }
