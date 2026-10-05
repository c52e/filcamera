plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "io.github.c52e.filcamera.rawvulkan"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    sourceSets.getByName("main").assets.srcDir(rootProject.file(".build/raw-vulkan/assets"))
    sourceSets.getByName("main").jniLibs.srcDir(rootProject.file(".build/raw-vulkan/jniLibs"))
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation(project(":imaging")) }
tasks.named("preBuild") {
    doLast { check(rootProject.file(".build/raw-vulkan/jniLibs/arm64-v8a/librawvulkan.so").isFile) {
        "Run python tools/build.py before packaging RAW Vulkan."
    } }
}

dependencies { implementation(project(":strings")) }
