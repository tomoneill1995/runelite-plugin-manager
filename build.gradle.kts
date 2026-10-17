subprojects {
    apply(plugin = "java")

    repositories {
        maven("https://repo.runelite.net")
        mavenCentral()
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
    }
}

// Code that runs inside RuneLite: compiled against the client, for its bundled Java 11
configure(listOf(project(":agent"), project(":test-plugin"))) {
    dependencies {
        "compileOnly"("net.runelite:client:${property("runeliteVersion")}")
    }

    tasks.withType<JavaCompile> {
        options.release.set(11)
    }
}
