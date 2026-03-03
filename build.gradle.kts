import net.ltgt.gradle.errorprone.errorprone

plugins {
    base
    id("net.ltgt.errorprone") version "5.0.0" apply false
    id("com.diffplug.spotless") version "8.2.1" apply false
}

// --- Git hook ---

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
    description = "Copy the Rust native library to the build directory"
    dependsOn(buildRust)
    from(rustTargetDir.dir("release").file(libFileName))
    into(layout.buildDirectory.dir("native"))
}

// --- Shared subproject configuration ---

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "checkstyle")
    apply(plugin = "jacoco")
    apply(plugin = "maven-publish")
    apply(plugin = "net.ltgt.errorprone")
    apply(plugin = "com.diffplug.spotless")

    group = "net.carcdr"
    version = "0.1.0-SNAPSHOT"

    repositories {
        mavenCentral()
    }

    configure<JavaPluginExtension> {
        toolchain {
            languageVersion = JavaLanguageVersion.of(22)
        }
        withSourcesJar()
    }

    configure<CheckstyleExtension> {
        toolVersion = "10.21.4"
        configFile = rootProject.file("config/checkstyle/checkstyle.xml")
    }

    configure<JacocoPluginExtension> {
        toolVersion = "0.8.12"
    }

    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            googleJavaFormat().aosp()
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
        }
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
        dependsOn(rootProject.tasks.named("copyNativeLib"))
        jvmArgs("--enable-preview", "--enable-native-access=ALL-UNNAMED")
        systemProperty(
            "java.library.path",
            rootProject.layout.buildDirectory.dir("native").get().asFile.absolutePath
        )
        useJUnitPlatform()
        finalizedBy(tasks.named("jacocoTestReport"))
    }

    tasks.withType<JacocoReport> {
        reports {
            html.required.set(true)
            xml.required.set(true)
        }
    }

    configure<PublishingExtension> {
        publications {
            create<MavenPublication>("maven") {
                from(components["java"])
            }
        }
        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/edpaget/datafusion-panama")
                credentials {
                    username = System.getenv("GITHUB_ACTOR")
                    password = System.getenv("GITHUB_TOKEN")
                }
            }
        }
    }

    dependencies {
        "errorprone"("com.google.errorprone:error_prone_core:2.48.0")
        "testImplementation"("org.junit.jupiter:junit-jupiter:5.11.4")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
}
