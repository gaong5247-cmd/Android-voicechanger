plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
 namespace = "dev.voiceshift"
 compileSdk = 35
 ndkVersion = "27.0.12077973"
 defaultConfig {
  applicationId = "dev.voiceshift"
  minSdk = 26
  targetSdk = 35
  versionCode = 1
  versionName = "0.1.0"
  ndk { abiFilters += providers.gradleProperty("voiceshift.testAbi").getOrElse("arm64-v8a") }
  externalNativeBuild { cmake { cppFlags += listOf("-std=c++17", "-Wall", "-Wextra", "-Werror") } }
 }
 buildFeatures { compose = true }
 buildTypes { release { isMinifyEnabled = false; signingConfig = signingConfigs.getByName("debug") } }
 compileOptions { sourceCompatibility=JavaVersion.VERSION_17; targetCompatibility=JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
 externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
}
dependencies {
 implementation("androidx.core:core-ktx:1.13.1")
 implementation("androidx.activity:activity-compose:1.9.0")
 implementation(platform("androidx.compose:compose-bom:2024.06.00"))
 implementation("androidx.compose.material3:material3")
 implementation("androidx.compose.ui:ui")
 implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
 testImplementation("junit:junit:4.13.2")
}
