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

// Hytale server jar as a compile-only dependency
dependencies {
    compileOnly(files("D:/Games/Hytale/install/release/package/game/latest/Server/HytaleServer.jar"))
}

tasks.named<Jar>("jar") {
    manifest {
        attributes(
            "Main-Class" to main_class
        )
    }
    from(sourceSets.main.get().output)
    // Include any resources
    from(sourceSets.main.get().resources)
}

tasks.register("copyToMods", Copy) {
    dependsOn(tasks.named("jar"))
    from(tasks.named("jar").get().archiveFile.get())
    into("D:/Games/Hytale/UserData/Mods")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.named("build") {
    dependsOn("copyToMods")
}

