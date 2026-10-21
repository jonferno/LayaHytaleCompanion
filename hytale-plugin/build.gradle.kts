plugins {
    idea
    java
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
}

// Point this at the Hytale server JAR for your installed version:
//   $env:HYTALE_SERVER_JAR = "D:\Games\Hytale\install\release\package\game\latest\Server\HytaleServer.jar"
// or pass -PhytaleServerJar=...
val hytaleServerJar: String =
    providers.environmentVariable("HYTALE_SERVER_JAR").orNull
        ?: (findProperty("hytaleServerJar") as String?)
        ?: ""

dependencies {
    if (hytaleServerJar.isBlank()) {
        throw GradleException("Set HYTALE_SERVER_JAR or -PhytaleServerJar to HytaleServer.jar")
    }
    compileOnly(files(hytaleServerJar))
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Main-Class" to (findProperty("main_class") as String? ?: "com.jonferno.laya.LayaPlugin")
        )
    }
    from(sourceSets.main.get().output)
    from(sourceSets.main.get().resources)
}

// Optional: copy the built JAR straight into the game.
//   $env:HYTALE_MODS_DIR = "D:\Games\Hytale\UserData\Mods"
val hytaleModsDir: String =
    providers.environmentVariable("HYTALE_MODS_DIR").orNull
        ?: (findProperty("hytaleModsDir") as String?)
        ?: ""

if (hytaleModsDir.isNotBlank()) {
    tasks.register<Copy>("copyToMods") {
        dependsOn(tasks.named("jar"))
        from(tasks.named<Jar>("jar").flatMap { it.archiveFile })
        into(hytaleModsDir)
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }
    tasks.named("build") {
        dependsOn("copyToMods")
    }
}
