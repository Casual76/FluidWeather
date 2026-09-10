// Il banco di anteprima del cielo: una build Gradle a se', fuori dall'app.
//
// Sta fuori di proposito: usa Compose Desktop e la SUA versione di Kotlin, e non deve entrare nel
// grafo dell'APK. Si lancia dalla radice del repo con:
//
//   ./gradlew.bat -p tools/skypreview run
//
// e lascia i fotogrammi in tools/skypreview/out/. Vedi SkyPreview.kt.
pluginManagement {
  repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
  }
}

dependencyResolutionManagement {
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "skypreview"
