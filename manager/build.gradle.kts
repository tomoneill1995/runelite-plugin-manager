plugins {
    application
}

dependencies {
    implementation("com.formdev:flatlaf:3.5.4")
    implementation("com.google.code.gson:gson:2.11.0")
}

tasks.withType<JavaCompile> {
    // Java 11 so it runs on RuneLite's bundled JRE, whatever java is on PATH
    options.release.set(11)
}

application {
    mainClass.set("dev.rlsideloader.manager.ManagerApp")
}

// Ship the agent inside the manager so there's one file to run
tasks.named<ProcessResources>("processResources") {
    from(project(":agent").tasks.named("jar"))
}

// Self-contained jar: java -jar rl-sideloader-manager.jar
tasks.named<Jar>("jar") {
    archiveFileName.set("rl-sideloader-manager.jar")
    manifest {
        attributes("Main-Class" to "dev.rlsideloader.manager.ManagerApp")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    from({ configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) } })
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
