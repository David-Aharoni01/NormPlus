plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

application {
    mainClass.set("com.norm2hacked.cli.MainKt")
    applicationName = "normlink-cli"
}

dependencies {
    implementation(project(":protocol"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation(libs.clikt)
}

kotlin {
    jvmToolchain(17)
}

// Bundle the Python (bleak) BLE backend into the install image at <APP_HOME>/app/python,
// where PythonBridgeTransport resolves it at runtime (env APP_HOME, set by the start scripts).
distributions {
    main {
        contents {
            from("src/main/python") {
                into("app/python")
                exclude("**/__pycache__/**", "**/*.pyc")
            }
        }
    }
}

// Fat JAR so the CLI runs standalone: java -jar normlink-cli.jar
tasks.register<Jar>("fatJar") {
    archiveClassifier.set("standalone")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    manifest { attributes["Main-Class"] = application.mainClass.get() }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    with(tasks.jar.get())
}
