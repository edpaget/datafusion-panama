plugins {
    java
    application
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(22)
    }
}

tasks.withType<JavaCompile> {
    options.compilerArgs.addAll(listOf("--enable-preview"))
}

tasks.withType<JavaExec> {
    jvmArgs("--enable-preview", "--enable-native-access=ALL-UNNAMED")
}

tasks.withType<Test> {
    jvmArgs("--enable-preview", "--enable-native-access=ALL-UNNAMED")
    useJUnitPlatform()
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
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
