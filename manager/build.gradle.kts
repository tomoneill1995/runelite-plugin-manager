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

// ---- Windows app: RuneLite Plugin Manager.exe with its own trimmed Java runtime ----

val appName = "RuneLite Plugin Manager"
val appVersion = property("appVersion").toString()
val packageInput = layout.buildDirectory.dir("package-input")
val packageOutput = layout.buildDirectory.dir("package")

// jpackage ships with JDK 14+, so use a JDK 17 toolchain whatever Java runs Gradle
val jdk17 = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) }

val stagePackageInput by tasks.registering(Sync::class) {
    from(tasks.named("jar"))
    into(packageInput)
}

val packageApp by tasks.registering(Exec::class) {
    group = "distribution"
    description = "Builds build/package/$appName/$appName.exe"
    dependsOn(stagePackageInput)
    inputs.dir(packageInput)
    inputs.file("packaging/app.ico")
    outputs.dir(packageOutput)

    doFirst {
        delete(packageOutput)
        val jpackage = jdk17.get().metadata.installationPath.file("bin/jpackage.exe").asFile
        commandLine(
            jpackage.absolutePath,
            "--type", "app-image",
            "--name", appName,
            "--app-version", appVersion,
            "--vendor", "tomoneill1995",
            "--description", "Sideload and hot reload RuneLite plugins",
            "--icon", file("packaging/app.ico").absolutePath,
            "--input", packageInput.get().asFile.absolutePath,
            "--main-jar", "rl-sideloader-manager.jar",
            "--main-class", "dev.rlsideloader.manager.ManagerApp",
            "--dest", packageOutput.get().asFile.absolutePath,
            // Swing, HTTPS to the Plugin Hub (EC ciphers), Gson/FlatLaf (Unsafe, java.sql)
            "--add-modules", "java.base,java.desktop,java.logging,java.net.http,java.sql,jdk.unsupported,jdk.crypto.ec,jdk.charsets,jdk.localedata",
            "--jlink-options", "--strip-debug --no-man-pages --no-header-files --compress=2"
        )
    }
}

val zipApp by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Zips the Windows app for a GitHub release"
    dependsOn(packageApp)
    from(packageOutput)
    archiveFileName.set("RuneLite-Plugin-Manager-$appVersion-windows.zip")
    destinationDirectory.set(layout.buildDirectory.dir("release"))
}
