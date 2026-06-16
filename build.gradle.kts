import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask

plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.16.0"
    id("com.adarshr.test-logger") version "4.0.0"
}

group = "org.jetbrains.research.anticopypaster"
version = "2026.1-3.3" //version of the plugin, not the platform

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

repositories {
    maven("https://plugins.gradle.org/m2/")
    maven("https://packages.jetbrains.team/maven/p/big-code/bigcode")
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(properties("platformVersion"))
        bundledPlugins(
            properties("platformBundledPlugins")
                .split(',')
                .map(String::trim)
                .filter(String::isNotEmpty)
        )
        testFramework(TestFrameworkType.Platform)
        testFramework(TestFrameworkType.Plugin.Java)
    }

    implementation("com.google.code.gson:gson:2.10.1")
    implementation("org.apache.commons:commons-lang3:3.12.0")
    implementation("org.pmml4s:pmml4s_3:1.0.1")
    implementation("org.mongodb:mongodb-driver-sync:4.10.1")
    implementation("com.github.javaparser:javaparser-core:3.0.0-alpha.4")
    implementation("commons-io:commons-io:1.3.2")
    implementation("args4j:args4j:2.33")
    implementation("org.jetbrains:annotations:24.0.1")
    implementation("org.apache.commons:commons-compress:1.21")
    /**
     * This file is commented out as it uses the TensorFlow API. By removing that dependency,
     * the plugin will be a fifth of the size and much more lightweight, but this won't
     * compile. It's been left here to allow for adding a feature to swap between models
     * in the future.
     *
     * This file is uncommented for now
     */
    implementation("org.tensorflow:tensorflow:1.15.0")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.9.3")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.9.3")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.9.3")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.9.3")
    testImplementation("org.mockito:mockito-core:5.18.0")
    testImplementation("org.mockito:mockito-junit-jupiter:5.18.0")
}

sourceSets {
    main {
        resources {
            setSrcDirs(listOf("src/main/resources"))
            exclude("**/*.index")
            exclude("**/*.meta")
            exclude("**/*.data*")
            exclude("**/saved_model*/**")
            exclude("code2vec/**")
            exclude("java14m/**")
            exclude("**/java14m/**")
        }
    }
}

tasks.withType<Jar>().configureEach {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude(
        "venv/**", ".venv/**",
        "node_modules/**",
        ".idea/**", ".git/**", "build/**",
        "**/*.log", "logs/**",
        "models/**", "aider/**", "sample_repos/**", "datasets/**", "tmp/**",
        "code2vec/**"
    )
}

fun properties(key: String) = project.findProperty(key).toString()

intellijPlatform {
    buildSearchableOptions.set(false)
    projectName.set("AntiCopyPaster")
    sandboxContainer.set(layout.buildDirectory.dir("idea-sandbox"))

    pluginConfiguration {
        ideaVersion {
            sinceBuild.set(properties("pluginSinceBuild"))
            untilBuild.set(provider { null })
        }
    }

    publishing {
        token.set(providers.gradleProperty("publishToken").orElse(providers.environmentVariable("PUBLISH_TOKEN")))
    }
}

tasks {
    //test task
    test {
        useJUnitPlatform()
        jvmArgs("-Dnet.bytebuddy.experimental=true")
    }

    withType<PrepareSandboxTask>().configureEach {
        from(projectDir) {
            include("code2vec/**")
            into(pluginName)
        }
    }

    named<JavaExec>("runIde") {
        maxHeapSize = "1g"
    }
}
