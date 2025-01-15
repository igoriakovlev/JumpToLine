import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
  id("java")
  id("org.jetbrains.kotlin.jvm") version "2.1.0"
  id("org.jetbrains.intellij.platform") version "2.2.1"
}

repositories {
  mavenCentral()
  intellijPlatform {
    defaultRepositories()
    snapshots()
  }
}

dependencies {
  testImplementation("org.junit.jupiter:junit-jupiter:5.7.1")
  runtimeOnly(files("${System.getProperty("java.home")}/lib/tools.jar"))
  runtimeOnly(files("${System.getProperty("java.home")}/lib/sa-jdi.jar"))

  // https://mvnrepository.com/artifact/org.ow2.asm/asm
  implementation("org.ow2.asm:asm:9.4")
  // https://mvnrepository.com/artifact/org.ow2.asm/asm-commons
  implementation("org.ow2.asm:asm-commons:9.4")
  // https://mvnrepository.com/artifact/org.ow2.asm/asm-util
  implementation("org.ow2.asm:asm-util:9.4")
}

// Configure Gradle IntelliJ Plugin
// Read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin.html
dependencies {
  intellijPlatform {
    intellijIdeaCommunity("251.14649-EAP-CANDIDATE-SNAPSHOT", useInstaller = false)
    bundledPlugin("com.intellij.java")
//    local("${System.getProperty("user.home")}/Applications/IntelliJ IDEA Community Edition.app")
  }
}

intellijPlatform {
  pluginConfiguration {
    ideaVersion {
      sinceBuild = "251"
    }
  }
  buildSearchableOptions = false
}

java {
  sourceCompatibility = JavaVersion.VERSION_21
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
  compilerOptions {
      jvmTarget = JvmTarget.JVM_21
  }
}