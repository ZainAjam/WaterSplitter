plugins {
    kotlin("jvm") version "2.4.21" apply false
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}

// OneDrive locks files while syncing and breaks Gradle builds. On a machine whose project sits in a synced folder,
// set `wewrc.buildRoot=<folder outside OneDrive>` in ~/.gradle/gradle.properties and all build output goes there.
(findProperty("wewrc.buildRoot") as String?)?.let { root ->
    allprojects {
        layout.buildDirectory.set(file("$root/${project.name}"))
    }
}
