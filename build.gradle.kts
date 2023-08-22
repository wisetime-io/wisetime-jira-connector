import io.wisetime.version.model.LegebuildConst

/*
 * Copyright (c) 2018 Practice Insight Pty Ltd. All Rights Reserved.
 */

buildscript {
  repositories {
    mavenCentral()
    maven {
      // WT published releases
      setUrl("https://s3.eu-central-1.amazonaws.com/artifacts.wisetime.com/mvn2/plugins")
      content {
        includeGroup("io.wisetime")
      }
    }
  }
  dependencies {
    // https://github.com/GoogleContainerTools/jib/issues/1018
    classpath("org.apache.httpcomponents:httpclient:4.5.12") {
      setForce(true)
    }
  }
}

plugins {
  java
  idea
  id("application")
  id("maven-publish")
  id("io.freefair.lombok") version "6.4.1"
  id("fr.brouillard.oss.gradle.jgitver") version "0.9.1"
  id("com.google.cloud.tools.jib") version "3.2.1"
  id("com.github.ben-manes.versions") version "0.39.0"
  id("io.wisetime.versionChecker")
}

apply(from = "$rootDir/gradle/conf/checkstyle.gradle")
apply(from = "$rootDir/gradle/conf/jacoco.gradle")

java {
  toolchain {
    languageVersion.set(JavaLanguageVersion.of(11))
    vendor.set(JvmVendorSpec.ADOPTOPENJDK)
    implementation.set(JvmImplementation.J9)
  }
  consistentResolution {
    useCompileClasspathVersions()
  }
}

application {
  mainClass.set("io.wisetime.connector.jira.ConnectorLauncher")
}
group = "io.wisetime"

jib {
  val targetArch = project.properties["targetArch"] ?: ""
  if (targetArch == "arm64v8") {
    from {
      image = "arm64v8/openjdk:11.0.8-jdk-buster"
    }
    to {
      project.afterEvaluate { // <-- so we evaluate version after it has been set
        image = "wisetime/wisetime-jira-connector-arm64v8:${project.version}"
      }
    }
  } else {
    from {
      image = "europe-west3-docker.pkg.dev/wise-pub/tools/connect-java-11-j9@sha256:d4e0e6d00a6babc29b68dd3ae28f46d00400b25b5ee82d9961bb0ecb08970215"
    }
    to {
      project.afterEvaluate { // <-- so we evaluate version after it has been set
        image = "wisetime/wisetime-jira-connector:${project.version}"
      }
    }
  }
}

repositories {
  mavenCentral()
  maven {
    // WiseTime artifacts
    setUrl("https://s3.eu-central-1.amazonaws.com/artifacts.wisetime.com/mvn2/releases")
    content {
      includeGroup("io.wisetime")
    }
  }
}

tasks.withType(com.google.cloud.tools.jib.gradle.JibTask::class.java) {
  dependsOn(tasks.compileJava)
}

val taskRequestString = gradle.startParameter.taskRequests.toString()
if (taskRequestString.contains("dependencyUpdates")) {
  // add exclusions for reporting on updates and vulnerabilities
  apply(from = "$rootDir/gradle/versionPluginConfig.gradle")
}

dependencies {
  implementation("io.wisetime:wisetime-connector:5.0.23")
  implementation("com.google.inject:guice:${LegebuildConst.GUICE_GOOGLE}") {
    exclude(group = "com.google.guava", module = "guava")
  }
  implementation("com.google.guava:guava:${LegebuildConst.GUAVA_VERSION}")
  implementation("org.apache.commons:commons-lang3:3.12.0")
  implementation("commons-codec:commons-codec:1.15")
  implementation("com.fasterxml.jackson.core:jackson-core:${LegebuildConst.JACKSON_FASTER}")
  implementation("com.fasterxml.jackson.core:jackson-databind:${LegebuildConst.JACKSON_FASTER}")
  implementation("com.vdurmont:emoji-java:5.1.1")
  implementation("org.codejargon:fluentjdbc:1.8.6")
  implementation("com.zaxxer:HikariCP:4.0.3")
  implementation("mysql:mysql-connector-java:5.1.44")
  implementation("org.postgresql:postgresql:42.2.13")
  implementation("joda-time:joda-time:${LegebuildConst.JODA_TIME}")

  testImplementation("org.junit.jupiter:junit-jupiter:5.8.2")
  testImplementation("org.mockito:mockito-core:4.3.1")
  testImplementation("org.assertj:assertj-core:3.22.0")
  testImplementation("com.github.javafaker:javafaker:1.0.2") {
    exclude(group = "org.apache.commons", module = "commons-lang3")
  }
  testImplementation("org.flywaydb:flyway-core:8.5.4")
  testRuntimeOnly("com.h2database:h2:2.1.210")
}

configurations.all {
  resolutionStrategy {
    eachDependency {
      if (requested.group == "com.fasterxml.jackson.core") {
        useVersion(LegebuildConst.JACKSON_FASTER)
        because("use consistent version for all transitive dependencies")
      }
      if (requested.name == "commons-lang3") {
        useVersion("3.12.0")
        because("use consistent version for all transitive dependencies")
      }
      force("joda-time:joda-time:${LegebuildConst.JODA_TIME}")
    }
  }
}

tasks.test {
  useJUnitPlatform()
  testLogging {
    // "passed", "skipped", "failed"
    events("skipped", "failed")
    exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
  }
}

tasks.clean {
  delete("${projectDir}/out")
}

jgitver {
  autoIncrementPatch(false)
  strategy(fr.brouillard.oss.jgitver.Strategies.PATTERN)
  versionPattern("\${meta.CURRENT_VERSION_MAJOR}.\${meta.CURRENT_VERSION_MINOR}.\${meta.COMMIT_DISTANCE}")
  regexVersionTag("v(\\d+\\.\\d+(\\.0)?)")
}

publishing {
  repositories {
    maven {
      setUrl("s3://artifacts.wisetime.com/mvn2/releases")
      authentication {
        val awsIm by registering(AwsImAuthentication::class)
      }
    }
  }

  publications {
    register("mavenJava", MavenPublication::class) {
      artifactId = "wisetime-jira-connector"
      from(components["java"])
    }
  }
}

tasks.register<DefaultTask>("printVersionStr") {
  doLast {
    println("${project.version}")
  }
}
