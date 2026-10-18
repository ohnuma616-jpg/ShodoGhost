plugins {
    kotlin("jvm")
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    testImplementation(kotlin("test-junit5"))
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
}

tasks.register<JavaExec>("loopback") {
    group = "application"
    description = "Serve one mock GATT session on TCP. Args: <port> <ready-file>"
    classpath = sourceSets.named("main").get().runtimeClasspath
    mainClass.set("dev.shodoghost.protocol.loopback.LoopbackGlassHost")
    standardInput = System.`in`
}
