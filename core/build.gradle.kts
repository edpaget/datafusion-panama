plugins {
    application
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
