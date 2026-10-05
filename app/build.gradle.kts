import java.util.Properties

plugins { id("com.android.application"); kotlin("android"); id("org.jetbrains.kotlin.plugin.compose") }
val signingProperties = Properties().apply {
    rootProject.file("keystore.properties").takeIf { it.isFile }?.inputStream()?.use { load(it) }
}
fun signingValue(property: String, environment: String): String? =
    providers.environmentVariable(environment).orNull ?: signingProperties.getProperty(property) ?:
        providers.gradleProperty("android.injected.signing." + when(property) {
            "storeFile" -> "store.file"
            "storePassword" -> "store.password"
            "keyAlias" -> "key.alias"
            "keyPassword" -> "key.password"
            else -> property
        }).orNull
val releaseStore = signingValue("storeFile", "FILCAMERA_KEYSTORE")
val releaseAlias = signingValue("keyAlias", "FILCAMERA_KEY_ALIAS")
val releaseStorePassword = signingValue("storePassword", "FILCAMERA_STORE_PASSWORD")
val releaseKeyPassword = signingValue("keyPassword", "FILCAMERA_KEY_PASSWORD") ?: releaseStorePassword
val releaseSigningReady = listOf(releaseStore, releaseAlias, releaseStorePassword, releaseKeyPassword).all { !it.isNullOrBlank() }

android {
    namespace = "io.github.c52e.filcamera"
    compileSdk = 35
    defaultConfig {
        applicationId = "io.github.c52e.filcamera"
        minSdk = 29
        targetSdk = 35
        versionCode = 48
        versionName = "0.22.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += "arm64-v8a" }
    }
    sourceSets.getByName("main").jniLibs.srcDir(rootProject.file(".build/package/jniLibs"))
    sourceSets.getByName("main").assets.srcDir(rootProject.file(".build/package/assets"))
    for (variant in listOf("debug", "release")) {
        sourceSets.getByName(variant).assets.srcDir(rootProject.file(".build/runtime-notices/$variant"))
    }
    signingConfigs {
        create("release") {
            if (releaseSigningReady) {
                storeFile = rootProject.file(requireNotNull(releaseStore))
                storePassword = releaseStorePassword
                keyAlias = releaseAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        getByName("debug") { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug" }
        getByName("release") { signingConfig = signingConfigs.getByName("release") }
    }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
tasks.named("preBuild") {
    doLast { check(rootProject.file(".build/package/jniLibs/arm64-v8a/libfilcamera_film.so").isFile) {
        "Run python tools/build.py first to build the native libraries and verify model assets."
    } }
}
dependencies {
    implementation(project(":camera"))
    implementation(project(":preview"))
    implementation(project(":imaging"))
    implementation(project(":raw-vulkan"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.core:core-ktx:1.15.0")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}

dependencies { implementation(project(":strings")) }

val validateReleaseCredentials = tasks.register("validateReleaseCredentials") {
    doLast {
        check(releaseSigningReady) { "Release signing is not configured. Run python tools/build.py --release in your terminal." }
        check(rootProject.file(requireNotNull(releaseStore)).isFile) { "Release keystore does not exist." }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(validateReleaseCredentials) }

// Resolve the exact shipped dependency graph; local paths stay in ignored build output.
for (variant in listOf("debug", "release")) {
    tasks.register("export${variant.replaceFirstChar { it.uppercase() }}RuntimeInventory") {
        doLast {
            val configuration = configurations.getByName("${variant}RuntimeClasspath")
            val artifacts = configuration.incoming.artifactView { componentFilter { it is org.gradle.api.artifacts.component.ModuleComponentIdentifier } }.artifacts.artifacts
            fun parentPoms(file: java.io.File, depth: Int = 0): List<String> {
                check(depth < 10) { "POM parent chain exceeds limit" }
                val factory = javax.xml.parsers.DocumentBuilderFactory.newInstance()
                factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                val document = factory.newDocumentBuilder().parse(file)
                if (document.getElementsByTagName("licenses").length > 0) return emptyList()
                val parent = document.getElementsByTagName("parent").item(0) as? org.w3c.dom.Element ?: return emptyList()
                fun field(name: String) = parent.getElementsByTagName(name).item(0).textContent.trim()
                val next = configurations.detachedConfiguration(dependencies.create(
                    "${field("groupId")}:${field("artifactId")}:${field("version")}@pom"
                )).apply { isTransitive = false }.singleFile
                return listOf(next.absolutePath) + parentPoms(next, depth + 1)
            }
            val rows = configuration.incoming.resolutionResult.allComponents.mapNotNull { component ->
                val id = component.id as? org.gradle.api.artifacts.component.ModuleComponentIdentifier
                    ?: return@mapNotNull null
                val pom = configurations.detachedConfiguration(
                    dependencies.create("${id.group}:${id.module}:${id.version}@pom")
                ).apply { isTransitive = false }.singleFile
                mapOf("coordinate" to "${id.group}:${id.module}:${id.version}",
                    "pom" to pom.absolutePath, "parentPoms" to parentPoms(pom),
                    "artifacts" to artifacts.filter { (it.id.componentIdentifier as org.gradle.api.artifacts.component.ModuleComponentIdentifier).group == id.group &&
                        (it.id.componentIdentifier as org.gradle.api.artifacts.component.ModuleComponentIdentifier).module == id.module && (it.id.componentIdentifier as org.gradle.api.artifacts.component.ModuleComponentIdentifier).version == id.version }
                        .map { it.file.absolutePath })
            }.sortedBy { it["coordinate"].toString() }
            val output = rootProject.file(".build/dependencies/$variant-resolved.json")
            output.parentFile.mkdirs()
            output.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(rows)))
        }
    }
}


// Both IDE/Gradle and Python builds must refresh notices and verify native inputs.
for (variant in listOf("debug", "release")) {
    val capitalized = variant.replaceFirstChar { it.uppercase() }
    val prepare = tasks.register<Exec>("prepare${capitalized}ProductAssets") {
        dependsOn("export${capitalized}RuntimeInventory")
        workingDir(rootProject.projectDir)
        val python = providers.gradleProperty("filcamera.python").orElse(providers.environmentVariable("FILCAMERA_PYTHON")).orElse("python")
        commandLine(python.get(), "tools/prepare_android.py", "--variant", variant)
    }
    tasks.matching { it.name == "pre${capitalized}Build" || it.name == "merge${capitalized}Assets" }.configureEach {
        dependsOn(prepare)
    }
}
