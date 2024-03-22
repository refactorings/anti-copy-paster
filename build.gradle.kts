plugins {
    java
    id("org.jetbrains.intellij") version "1.15.0"
    id("com.adarshr.test-logger") version "3.2.0"
}

group = "org.jetbrains.research.anticopypaster"
version = "2023.2-2.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
}

repositories {
    maven("https://plugins.gradle.org/m2/")
    maven("https://packages.jetbrains.team/maven/p/big-code/bigcode")
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.apache.commons:commons-lang3:3.12.0")
    implementation("org.pmml4s:pmml4s_3:1.0.1")
    implementation("org.mongodb:mongodb-driver-sync:4.10.1")
    implementation("com.github.javaparser:javaparser-core:3.0.0-alpha.4")
    implementation("commons-io:commons-io:1.3.2")
    implementation("args4j:args4j:2.33")
    implementation("org.jetbrains:annotations:24.0.1")
    /**
     * This file is commented out as it uses the TensorFlow API. By removing that dependency,
     * the plugin will be a fifth of the size and much more lightweight, but this won't
     * compile. It's been left here to allow for adding a feature to swap between models
     * in the future.
     */
    // implementation("org.tensorflow:tensorflow:1.15.0")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.9.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.3")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.9.3")
    testImplementation("org.mockito:mockito-core:5.4.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.4.0")
}

fun properties(key: String) = project.findProperty(key).toString()
fun config(name: String) = project.findProperty(name).toString()
val ideaVersion = config("ideaVersion")
intellij {
    version.set(properties("platformVersion"))
    type.set(properties("platformType"))
    downloadSources.set(properties("platformDownloadSources").toBoolean())
    updateSinceUntilBuild.set(true)
    plugins.set(properties("platformPlugins").split(',').map(String::trim).filter(String::isNotEmpty))
    plugins.add("terminal")
    if (ideaVersion.contains("PC")) {
        plugins.add("python-ce")
    } else if (ideaVersion.contains("PY")) {
        plugins.add("python")
    }
}

tasks {
    withType<org.jetbrains.intellij.tasks.BuildSearchableOptionsTask>()
        .forEach { it.enabled = false }
    runIde {
        maxHeapSize = "1g"
    }
    //test task
    test {
        useJUnitPlatform()
    }
    val copyStubs = register<Copy>("copyStubs") {
        dependsOn("prepareSandbox")
        from(projectDir) {
            include("code2vec/")
        }
        into("${intellij.sandboxDir.get()}/plugins/AntiCopyPaster")
    }
    buildSearchableOptions {
        dependsOn(copyStubs)
    }
    buildPlugin {
        dependsOn(copyStubs)
    }
    runIde {
        dependsOn(copyStubs)
    }
    publishPlugin {
        token = config("publishToken")
    }
}

