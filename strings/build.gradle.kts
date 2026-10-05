plugins { id("com.android.library"); kotlin("android") }
android {
    namespace = "io.github.c52e.filcamera.strings"
    compileSdk = 35
    defaultConfig { minSdk = 29 }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
