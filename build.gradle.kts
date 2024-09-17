@file:Suppress("ConvertLambdaToReference")

import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
	kotlin("jvm") version "1.8.0"
	id("org.jetbrains.intellij") version "1.17.4"
}

group = "com.intellij.support.ide.inspector"
version = "1.0.0"

repositories {
	mavenCentral()
}

intellij {
	version.set("2024.2.1")
	type.set("IC")
	updateSinceUntilBuild.set(false)
	
	plugins.add("tanvd.grazi")
	plugins.add("com.intellij.java")
}

kotlin {
	jvmToolchain(17)
}

tasks {
	// Set the compatibility versions to 1.8
	withType<JavaCompile> {
		sourceCompatibility = "17"
		targetCompatibility = "17"
		options.encoding = "UTF-8"
	}
	withType<KotlinCompile> {
		kotlinOptions.jvmTarget = "17"
	}
}

dependencies {
	testImplementation("org.junit.jupiter:junit-jupiter:5.9.2")
}

tasks.patchPluginXml {
	sinceBuild.set("233.11361.10")
}

tasks.buildSearchableOptions {
	enabled = false
}

tasks.test {
	useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
	kotlinOptions.freeCompilerArgs = listOf(
		"-Xjvm-default=all"
	)
}

tasks.instrumentCode {
    enabled = false
}

tasks.instrumentTestCode {
	enabled = false
}