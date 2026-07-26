plugins {
	java
	id("com.gradleup.shadow") version "9.4.1"
}

group = "net.luis"
version = "1.0.0"

java {
	toolchain {
		languageVersion.set(JavaLanguageVersion.of(25))
	}
}

repositories {
	// shared-core (sudoku-lib) has never been published remotely, so it resolves from ~/.m2.
	// LUtils resolves from the Artifactory - it's reachable for fetching, just not currently
	// publishable to (that's a separate, unrelated outage).
	mavenLocal()
	maven {
		url = uri("https://maven.luis-st.net/libraries/")
	}
	mavenCentral()
}

dependencies {
	// shared-core (grid, generator, solver, key derivation) - exact version, never a range
	implementation(libs.sudoku.lib)

	// LUtils - its net.luis.utils.io.database package is this server's SQL layer.
	// The published POM carries no dependency metadata, so LUtils' own runtime requirements are
	// declared here by hand; dropping any of them fails at runtime rather than at compile time.
	implementation(libs.lutils)
	implementation(libs.guava)
	implementation(libs.apache.commons.lang3)
	implementation(libs.jetbrains.annotations)

	// Javalin
	implementation(libs.javalin)
	implementation(libs.javalin.bundle)
	
	// OpenAPI
	implementation(libs.javalin.openapi.plugin)
	implementation(libs.javalin.swagger.plugin)
	annotationProcessor(libs.javalin.openapi.annotation.processor)
	
	// Jackson JSON
	implementation(libs.jackson.databind)
	
	// JWT (nimbus-jose-jwt)
	implementation(libs.nimbus.jose.jwt)
	
	// Logging
	implementation(libs.log4j2.api)
	implementation(libs.log4j2.core)
	implementation(libs.log4j2.slf4j2.impl)
	
	// Database
	implementation(libs.hikaricp)
	implementation(libs.postgresql)
	
	// Nullability
	implementation(libs.jspecify)
	
	// Testing
	testImplementation(platform("org.junit:junit-bom:6.0.0"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")

	// Integration tests run against a real Postgres: the invariants that matter here are transactional
	// (advisory locks, conditional updates, FOR UPDATE) and an in-memory database cannot prove them.
	testImplementation(libs.testcontainers.postgresql)
	testImplementation(libs.testcontainers.junit)
}

tasks.test {
	useJUnitPlatform()
}

tasks.register<JavaExec>("run") {
	description = "Runs the application"
	group = "api"
	
	mainClass.set("net.luis.sudoku.Application")
	classpath = sourceSets["main"].runtimeClasspath
	
	enableAssertions = true
	standardInput = System.`in`
	args = listOf()
}

val generateOpenApi = tasks.register("generateOpenApi") {
	description = "Generates the OpenAPI specification file"
	group = "api"
	
	dependsOn(tasks.compileJava)
	
	val source = layout.buildDirectory.file("classes/java/main/openapi-plugin/openapi-default.json")
	val target = layout.projectDirectory.file("openapi.json")
	
	inputs.file(source)
	outputs.file(target)
	
	doLast {
		source.get().asFile.copyTo(target.asFile, overwrite = true)
	}
}

tasks.compileJava {
	finalizedBy(generateOpenApi)
}

tasks.shadowJar {
	group = "api"
	
	archiveClassifier.set("")
	mergeServiceFiles()
	manifest {
		attributes("Main-Class" to "net.luis.sudoku.Application")
	}
}

