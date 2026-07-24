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
	mavenCentral()
}

dependencies {
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
	
	// Nullability
	implementation(libs.jspecify)
	
	// Testing
	testImplementation(platform("org.junit:junit-bom:6.0.0"))
	testImplementation("org.junit.jupiter:junit-jupiter")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
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
