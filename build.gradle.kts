import net.ltgt.gradle.errorprone.errorprone

plugins {
    java
    application
    checkstyle
    jacoco
    id("net.ltgt.errorprone") version "5.0.0"
    id("com.diffplug.spotless") version "8.2.1"
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(22)
    }
}

checkstyle {
    toolVersion = "10.21.4"
    configFile = file("config/checkstyle/checkstyle.xml")
}

jacoco {
    toolVersion = "0.8.12"
}

spotless {
    java {
        googleJavaFormat().aosp()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
}

val installGitHook by tasks.registering(Copy::class) {
    description = "Install the pre-commit git hook"
    from("hooks/pre-commit")
    into(".git/hooks")
    filePermissions {
        unix("rwxr-xr-x")
    }
}

tasks.named("check") {
    dependsOn(installGitHook)
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("--enable-preview"))
    options.errorprone {
        disableWarningsInGeneratedCode.set(true)
    }
}

tasks.withType<JavaExec> {
    jvmArgs("--enable-preview", "--enable-native-access=ALL-UNNAMED")
}

tasks.withType<Test> {
    jvmArgs("--enable-preview", "--enable-native-access=ALL-UNNAMED")
    useJUnitPlatform()
    finalizedBy(tasks.jacocoTestReport)
}

tasks.jacocoTestReport {
    reports {
        html.required.set(true)
        xml.required.set(true)
    }
}

// --- Rust native library build ---

val rustDir = layout.projectDirectory.dir("rust")
val rustTargetDir = rustDir.dir("target")
val rustLibName = "datafusion_panama"

val buildRust by tasks.registering(Exec::class) {
    description = "Build the Rust native library"
    workingDir = rustDir.asFile
    commandLine("cargo", "build", "--release")

    inputs.dir(rustDir.dir("src"))
    inputs.file(rustDir.file("Cargo.toml"))
    outputs.dir(rustTargetDir.dir("release"))
}

// Detect the platform-specific library filename
val osName: String = System.getProperty("os.name").lowercase()
val libFileName = when {
    osName.contains("mac") || osName.contains("darwin") -> "lib${rustLibName}.dylib"
    osName.contains("win") -> "${rustLibName}.dll"
    else -> "lib${rustLibName}.so"
}

val copyNativeLib by tasks.registering(Copy::class) {
    description = "Copy the Rust native library to the resources directory"
    dependsOn(buildRust)
    from(rustTargetDir.dir("release").file(libFileName))
    into(layout.buildDirectory.dir("native"))
}

tasks.named("processResources") {
    dependsOn(copyNativeLib)
}

tasks.named<JavaExec>("run") {
    dependsOn(copyNativeLib)
    systemProperty("java.library.path", layout.buildDirectory.dir("native").get().asFile.absolutePath)
}

application {
    mainClass = "com.github.edwardpaget.datafusionpanama.Main"
}

dependencies {
    errorprone("com.google.errorprone:error_prone_core:2.48.0")
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
