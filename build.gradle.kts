plugins {
    java
}

group = "net.aguiar"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") // Paper API
}

dependencies {
    // Paper API for MC 26.1.2. compileOnly: the server provides it at runtime.
    // Folia's region/global schedulers (Bukkit.getRegionScheduler / getGlobalRegionScheduler)
    // live in this same artifact and are no-op-compatible on plain Paper.
    // NOTE: paper-api 26.1.2 requires JVM 25+, so we compile with a Java 25 toolchain.
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.72-stable")
}

java {
    // Compile with JDK 25 (the servers run JDK 25, and paper-api 26.1.2 requires runtime 25+).
    // Gradle auto-detects the Temurin 25 under ~/.jdks. Gradle's own launcher runs on JDK 21,
    // which Gradle 8.12 supports; only the compiler uses this toolchain.
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
}

// No runtime dependencies beyond the Paper API (compileOnly), so the plain jar IS the plugin
// jar — no shadow/fat-jar needed. build/libs/ holds exactly one jar.
tasks.named<Jar>("jar") {
    archiveClassifier.set("")
}

tasks.processResources {
    // Let plugin.yml reference ${version}.
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}
