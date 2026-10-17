tasks.named<Jar>("jar") {
    archiveFileName.set("rl-sideloader-agent.jar")
    manifest {
        attributes("Premain-Class" to "dev.rlsideloader.agent.SideloadAgent")
    }
}
