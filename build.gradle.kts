plugins {
    java
    id("org.jetbrains.intellij") version "1.13.2"
    id("com.adarshr.test-logger") version "3.2.0"
}

group = "org.jetbrains.research.anticopypaster"
<<<<<<< Updated upstream
version = "2022.3-1.0"
=======
version = "2024.1-1.0"
>>>>>>> Stashed changes

java {
    sourceCompatibility = JavaVersion.VERSION_17
}

repositories {
    maven("https://plugins.gradle.org/m2/")
    maven("https://packages.jetbrains.team/maven/p/big-code/bigcode")
    mavenCentral()
}

dependencies {
    implementation("com.google.code.gson:gson:2.8.6")
    implementation("org.apache.commons:commons-lang3:3.0")
    implementation("org.pmml4s:pmml4s_2.13:0.9.10")
    /**
     * This file is commented out as it uses the TensorFlow API. By removing that dependency,
     * the plugin will be a fifth of the size and much more lightweight, but this won't
     * compile. It's been left here to allow for adding a feature to swap between models
     * in the future.
     */
    // implementation("org.tensorflow:tensorflow:1.15.0")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.9.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.2")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.9.2")
    testImplementation("org.mockito:mockito-inline:4.0.0")
    testImplementation("org.mockito:mockito-junit-jupiter:4.0.0")
    testImplementation("org.powermock:powermock-api-mockito2:2.0.9")
    testImplementation("org.powermock:powermock-module-junit4:2.0.9")
}

fun properties(key: String) = project.findProperty(key).toString()

intellij {
    version.set(properties("platformVersion"))
    type.set(properties("platformType"))
    downloadSources.set(properties("platformDownloadSources").toBoolean())
    updateSinceUntilBuild.set(true)
    plugins.set(properties("platformPlugins").split(',').map(String::trim).filter(String::isNotEmpty))
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
}

