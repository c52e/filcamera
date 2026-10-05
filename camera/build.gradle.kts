plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "io.github.c52e.filcamera.camera"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies { implementation(project(":imaging"));testImplementation("junit:junit:4.13.2") }

dependencies { implementation(project(":strings")) }
