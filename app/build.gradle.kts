import java.net.URI
import java.util.Properties
plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose"); id("org.jetbrains.kotlin.kapt") }
val signingFile = rootProject.file(".local/signing.properties")
val signingValues = Properties().apply { if (signingFile.exists()) signingFile.inputStream().use(::load) }
val relayBase = URI(providers.gradleProperty("jotRelayBaseUrl")
    .getOrElse("https://jot-companion.clark719664.workers.dev"))
require(relayBase.scheme == "https" && relayBase.host != null && relayBase.userInfo == null &&
    relayBase.path.orEmpty() in listOf("", "/") && relayBase.query == null && relayBase.fragment == null) {
    "jotRelayBaseUrl must be an HTTPS origin."
}
android {
    namespace = "com.jot.app"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.clark.jot"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "1.3.0"
        buildConfigField("String", "RELAY_ORIGIN", "\"${relayBase.resolve("/")}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    if (signingFile.exists()) {
        signingConfigs.create("personal") {
            storeFile = rootProject.file(signingValues.getProperty("storeFile"))
            storePassword = signingValues.getProperty("storePassword")
            keyAlias = signingValues.getProperty("keyAlias")
            keyPassword = signingValues.getProperty("keyPassword")
        }
    }
    buildTypes {
        release { isMinifyEnabled = false; if (signingFile.exists()) signingConfig = signingConfigs.getByName("personal") }
    }
    buildFeatures { compose = true; buildConfig = true }
    androidResources { noCompress += listOf("onnx", "ort") }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests.isReturnDefaultValues = true }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation(platform("androidx.compose:compose-bom:2024.09.03"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    kapt("androidx.room:room-compiler:2.8.5")
    kapt("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("com.google.code.gson:gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.stripe:paymentsheet:22.6.1")
    implementation(fileTree("libs") { include("*.aar") })
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.09.03"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
