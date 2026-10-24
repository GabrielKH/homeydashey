import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
    implementation(libs.socketio.client)
    // Reads recipes from web pages and Recipe Keeper exports.
    implementation(libs.jsoup)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

// CI is the only place tests run, so show the whole stack trace of a failure in its log.
tasks.test {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

// Asks a real AI brain every skill test case and writes build/evals/report.md; see EvalMain for the settings,
// e.g. ./gradlew :homey-api:evals -Peval.brain=gemini
tasks.register<JavaExec>("evals") {
    group = "verification"
    description = "Tests the assistant's skills against a real AI brain."
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("app.homeydash.agent.evals.EvalMainKt")
    workingDir = projectDir
    defaultCharacterEncoding = "UTF-8"
    jvmArgs("-Dstdout.encoding=UTF-8")
    project.properties.filterKeys { it.startsWith("eval.") }.forEach { (k, v) -> systemProperty(k, v.toString()) }
}
