plugins {
    `java-library`
    `maven-publish`
    signing
}

group = "com.marginfuse"
version = "0.2.0"
description = "MarginFuse server-side SDK. AI profitability guardrails: connect revenue to " +
    "per-request AI cost and stop loss-making requests before they run. Sends usage metadata " +
    "only, never prompts or responses."

java {
    withSourcesJar()
    withJavadocJar()
}

repositories { mavenCentral() }

dependencies {
    // No runtime dependencies. Deliberately.
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    // Java 11 for HttpClient, which is what keeps this package dependency free:
    // a library that drags in a JSON parser forces its version on every
    // application that embeds it, and that is an argument not worth having.
    //
    // `release` rather than a toolchain, so it compiles against the real Java 11
    // API signatures on whatever JDK the contributor already has, instead of
    // making everyone install an old one.
    options.release = 11
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

// Which JDK the tests execute on, as opposed to the one running Gradle.
//
// These are two different questions and the build used to conflate them. Gradle
// runs on the JDK that launched it and each Gradle release supports a bounded
// range, so pinning CI's JDK to the runtime under test caps how new a runtime
// can be tested at whatever Gradle allows. The artifact targets Java 11 and has
// to keep working on runtimes released long after this build file, so the test
// JVM is selected separately here.
val testJavaVersion = (findProperty("testJavaVersion") as String?)?.toInt()

tasks.test {
    useJUnitPlatform()
    testLogging { events("failed") }
    // So VersionTest can compare the constant against the version actually
    // being published, which lives here and nowhere the code can read.
    systemProperty("marginfuse.publishedVersion", project.version.toString())
    if (testJavaVersion != null) {
        javaLauncher = javaToolchains.launcherFor {
            languageVersion = JavaLanguageVersion.of(testJavaVersion)
        }
    }
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).addStringOption("Xdoclint:none", "-quiet")
}

/** Builds the conformance runner as a runnable jar with the SDK inside it. */
tasks.register<Jar>("conformanceRunner") {
    archiveFileName = "conformance-runner.jar"
    destinationDirectory = layout.buildDirectory.dir("conformance")
    manifest { attributes("Main-Class" to "com.marginfuse.ConformanceRunner") }
    from(sourceSets.main.get().output)
    from(sourceSets.test.get().output) {
        include("com/marginfuse/ConformanceRunner*.class")
    }
}

publishing {
    repositories {
        // The release workflow zips this directory and posts it to the Central
        // Portal API, rather than going through a publishing plugin that would
        // have to be trusted with signed artifacts.
        maven {
            name = "staging"
            url = uri(layout.buildDirectory.dir("staging"))
        }
    }
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name = "marginfuse"
                description = project.description
                url = "https://marginfuse.com"
                licenses {
                    license {
                        name = "MIT License"
                        url = "https://opensource.org/licenses/MIT"
                    }
                }
                developers {
                    developer {
                        name = "Pemira Labs"
                        url = "https://marginfuse.com"
                    }
                }
                scm {
                    url = "https://github.com/marginfuse/marginfuse-java"
                    connection = "scm:git:https://github.com/marginfuse/marginfuse-java.git"
                    developerConnection = "scm:git:ssh://git@github.com/marginfuse/marginfuse-java.git"
                }
            }
        }
    }
}

signing {
    // Maven Central requires signed artifacts. In CI the key arrives as an
    // ascii-armoured secret; locally an unsigned build is fine, so signing is
    // skipped when no key is configured rather than failing every build.
    val key = System.getenv("GPG_SIGNING_KEY")
    val password = System.getenv("GPG_SIGNING_PASSWORD")
    if (!key.isNullOrBlank()) {
        useInMemoryPgpKeys(key, password)
        sign(publishing.publications["maven"])
    }
}
