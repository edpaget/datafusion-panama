plugins {
    application
}

val arrowVersion = "18.2.0"

dependencies {
    implementation("org.apache.arrow:arrow-vector:$arrowVersion")
    implementation("org.apache.arrow:arrow-c-data:$arrowVersion")
    runtimeOnly("org.apache.arrow:arrow-memory-unsafe:$arrowVersion")
}

application {
    mainClass = "net.carcdr.datafusionpanama.Main"
}

tasks.named<JavaExec>("run") {
    dependsOn(rootProject.tasks.named("copyNativeLib"))
    systemProperty(
        "java.library.path",
        rootProject.layout.buildDirectory.dir("native").get().asFile.absolutePath
    )
}
