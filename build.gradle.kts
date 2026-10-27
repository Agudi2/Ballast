import org.gradle.api.tasks.options.Option

plugins {
    `java-library`
}

group = "dev.ballast"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val simulation by sourceSets.creating {
    compileClasspath += sourceSets.main.get().output
    runtimeClasspath += sourceSets.main.get().output
}
configurations[simulation.implementationConfigurationName].extendsFrom(configurations.implementation.get())
sourceSets.test {
    compileClasspath += simulation.output
    runtimeClasspath += simulation.output
}

abstract class SimulateTask : JavaExec() {
    @get:Input var rootSeed: String = "1"
    @get:Input var stepCount: String = "1000"
    @get:Input var runCount: String = "1"
    @get:Input var traceDirectory: String = "build/simulation"

    @Option(option = "seed", description = "Signed 64-bit root seed.")
    fun seed(value: String) { rootSeed = value }
    @Option(option = "steps", description = "Positive number of operations per run.")
    fun steps(value: String) { stepCount = value }
    @Option(option = "runs", description = "Positive number of derived seeded runs.")
    fun runs(value: String) { runCount = value }
    @Option(option = "trace-dir", description = "Directory for reproducible model-only traces.")
    fun traces(value: String) { traceDirectory = value }

    @TaskAction
    override fun exec() {
        setArgs(listOf("--seed", rootSeed, "--steps", stepCount, "--runs", runCount,
            "--trace-dir", traceDirectory))
        super.exec()
    }
}

tasks.register<SimulateTask>("simulate") {
    group = "verification"
    description = "Runs the Stage 1 reference model only; does not validate a production queue."
    classpath = simulation.runtimeClasspath
    mainClass = "dev.ballast.simulation.SimulationRunner"
    maxHeapSize = "256m"
    javaLauncher = javaToolchains.launcherFor(java.toolchain)
}

dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<JavaCompile>().configureEach {
    options.release = 21
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
}

val verifyJavaToolchain by tasks.registering {
    group = "verification"
    description = "Verifies that compilation and tests use Java 21."
    val compiler = javaToolchains.compilerFor(java.toolchain)
    val launcher = javaToolchains.launcherFor(java.toolchain)
    doLast {
        check(compiler.get().metadata.languageVersion.asInt() == 21)
        check(launcher.get().metadata.languageVersion.asInt() == 21)
        logger.lifecycle("Compiler: ${compiler.get().executablePath}")
        logger.lifecycle("Test runtime: ${launcher.get().executablePath}")
    }
}

tasks.test {
    dependsOn(verifyJavaToolchain)
    useJUnitPlatform()
    systemProperty("ballast.simulation.classpath", simulation.runtimeClasspath.asPath)
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
