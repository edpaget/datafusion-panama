dependencies {
    implementation(project(":core"))
}

publishing {
    publications {
        named<MavenPublication>("maven") {
            artifactId = "datafusion-panama-jdbc"
        }
    }
}
