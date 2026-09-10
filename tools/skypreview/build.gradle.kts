// Il banco di anteprima del cielo.
//
// Compila gli STESSI pittori dell'app (core-ui/SkyPainters.kt, SkyPalette.kt, SkyState.kt) sopra
// il modello puro (core-model) e li fa disegnare da Compose Desktop dentro una bitmap: e' Skia
// esattamente come sul telefono, quindi cio' che esce da qui e' cio' che vede l'utente, gradienti e
// modi di fusione compresi. Serve perche' un cielo "giusto per costruzione" non e' mai bastato:
// si giudica dai fotogrammi.
//
// Kotlin 2.1 e Compose Multiplatform 1.7.3 invece delle versioni dell'app: e' una build separata e
// puo' permettersele, e sono quelle gia' nella cache di questa macchina.
plugins {
  kotlin("jvm") version "2.1.0"
  id("org.jetbrains.compose") version "1.7.3"
  // Voluto dal plugin di Compose anche se qui non c'e' una @Composable: sono solo pittori.
  id("org.jetbrains.kotlin.plugin.compose") version "2.1.0"
  application
}

kotlin {
  jvmToolchain(17)
}

val repoRoot = layout.projectDirectory.dir("../..")

// I sorgenti dell'app entrano per copia in build/skysrc: un srcDir sulla cartella intera di core-ui
// porterebbe dentro anche le composable Android, che qui non compilano.
val stageSkySources by tasks.registering(Sync::class) {
  from(repoRoot.dir("core-model/src/main/kotlin"))
  from(repoRoot.dir("core-ui/src/main/kotlin")) {
    include("**/SkyPainters.kt", "**/SkyPalette.kt", "**/SkyState.kt", "**/Celestial.kt")
  }
  into(layout.buildDirectory.dir("skysrc"))
}

sourceSets {
  main {
    kotlin.srcDir(stageSkySources)
  }
}

dependencies {
  implementation(compose.desktop.currentOs)
}

application {
  mainClass.set("SkyPreviewKt")
  // Dove scrivere i PNG: la cartella del banco, ignorata da git.
  applicationDefaultJvmArgs = listOf("-Dskypreview.out=${layout.projectDirectory.dir("out").asFile.absolutePath}")
}
