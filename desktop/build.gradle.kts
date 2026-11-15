import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

/** Versionsnummer der Android-App (app/build.gradle.kts → versionName), damit beide gleich bleiben. */
val farbwerkVersion: String = rootProject.file("app/build.gradle.kts").takeIf { it.exists() }
    ?.readText()
    ?.let { Regex("versionName\\s*=\\s*\"([0-9.]+)\"").find(it)?.groupValues?.get(1) }
    ?: "0.3.3"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        // Die geteilten Android-Quellen nutzen experimentelle Compose-APIs.
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.ui.ExperimentalComposeUiApi",
            "-Xsuppress-warning=NOTHING_TO_INLINE",
        )
    }
}

sourceSets {
    main {
        // Kompatibilitätsschicht (android.*, androidx.* Nachbildungen) + unveränderte App-Quellen
        kotlin.srcDirs("src/main/kotlin", "../app/src/main/java")
        // Schriften (res/font/*.ttf) als Klassenpfad-Ressourcen unter "font/…"
        resources.srcDirs("src/main/resources", "../app/src/main/res")
    }
}

/** Versionsdatei als Ressource (CrashReporter/PackageManager lesen sie). */
val versionResources = layout.buildDirectory.dir("generated/versionResources")
val writeVersionResource by tasks.registering {
    val out = versionResources
    val version = farbwerkVersion
    inputs.property("version", version)
    outputs.dir(out)
    doLast {
        val f = out.get().file("farbwerk-version.properties").asFile
        f.parentFile.mkdirs()
        f.writeText("version=$version\n")
    }
}
sourceSets.main { resources.srcDir(writeVersionResource) }

tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // aus app/src/main/res werden nur die Schriften gebraucht
    exclude("drawable/**", "values/**", "values-*/**", "xml/**", "mipmap*/**")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.desktop.windows_x64)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    // org.json ist auf Android Teil der Plattform
    implementation("org.json:json:20240303")
    // dunkle Windows-Titelleiste (DwmSetWindowAttribute)
    implementation("net.java.dev.jna:jna-platform:5.15.0")
}

compose.desktop {
    application {
        mainClass = "at.techgaisi.farbwerk.desktop.MainKt"
        jvmArgs += listOf("-Xmx4g", "-Dfile.encoding=UTF-8", "-Dsun.java2d.uiScale.enabled=true")

        nativeDistributions {
            targetFormats(TargetFormat.Exe, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "Farbwerk"
            packageVersion = farbwerkVersion
            description = "Farbwerk – Zeichen- und Mal-App"
            vendor = "techgaisi"
            copyright = "© 2026 Lorenz Gaishofer"
            // Module, die die Compose-/AWT-Laufzeit braucht (Zwischenablage, Dateidialoge, Prefs)
            modules("java.desktop", "java.prefs", "java.logging", "jdk.unsupported")

            windows {
                iconFile.set(project.file("icons/farbwerk.ico"))
                menuGroup = "Farbwerk"
                shortcut = true
                menu = true
                perUserInstall = true
                dirChooser = true
                // feste Upgrade-UUID, damit neue Versionen die alte ersetzen
                upgradeUuid = "6f1b4c8e-2a7d-4e55-9a51-3c7f0d2b8e41"
            }
            linux {
                iconFile.set(project.file("icons/farbwerk.png"))
                packageName = "farbwerk"
                debMaintainer = "lorenz@farbwerk.local"
                menuGroup = "Graphics"
                appCategory = "graphics"
                shortcut = true
            }
        }
    }
}
