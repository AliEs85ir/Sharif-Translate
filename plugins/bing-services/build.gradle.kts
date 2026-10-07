plugins {
    id("buildsrc.convention.kotlin-jvm")
    alias(libs.plugins.kotlinPluginSerialization)
    alias(libs.plugins.shadow)
}

dependencies {
    testImplementation(kotlin("test"))
    implementation(project(":api"))
    implementation(project(":plugins:common"))
    implementation(libs.kotlinxSerialization)

    implementation(libs.java.diff.utils)
}

tasks.shadowJar {
    archiveBaseName.set("bing-services-plugin")
    archiveClassifier.set("")
    archiveVersion.set("")
}

// Preserve provenance when a plugin JAR is distributed separately.
tasks.processResources {
    from(rootProject.file("LICENSE"))
    from(rootProject.file("plugins/NOTICE.md"))
}
