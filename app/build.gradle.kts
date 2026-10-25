plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.android)
  alias(libs.plugins.compose.compiler)
  alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.example.xgestalkble"
    compileSdk = 36
    defaultConfig {
        applicationId = "com.samvaad.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
      compose = true
      aidl = false
      buildConfig = false
      shaders = false
    }

    // Reproducibly exported from the approved official model by export_android.py.
    sourceSets.getByName("main").assets.srcDir("$rootDir/local-translation/.state/android-assets")
    androidResources { noCompress += "onnx" }

    packaging {
      resources {
        excludes += "/META-INF/{AL2.0,LGPL2.1}"
      }
    }
}

kotlin {
    jvmToolchain(17)
}

val bundledTranslationFiles = listOf("encoder.onnx", "decoder.onnx", "tokenizer.onnx", "vocab.json", "LICENSE").map {
    file("$rootDir/local-translation/.state/android-assets/indictrans2/$it")
}
val verifyBundledTranslation by tasks.registering {
    inputs.files(bundledTranslationFiles)
    val modelFiles = bundledTranslationFiles.toList()
    doLast {
        check(modelFiles.all { it.isFile && it.length() > 0 }) {
            "Bundled offline translator is missing. Follow the Android export steps in INDICTRANS_SETUP.md before building."
        }
    }
}
tasks.named("preBuild").configure { dependsOn(verifyBundledTranslation) }

dependencies {
  // QR guest chat uses the free Firebase Authentication and Firestore services.
  implementation(platform("com.google.firebase:firebase-bom:34.19.0"))
  implementation("com.google.firebase:firebase-auth")
  implementation("com.google.firebase:firebase-firestore")
  implementation("com.google.firebase:firebase-functions")
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
  implementation("com.google.zxing:core:3.5.4")

  val composeBom = platform(libs.androidx.compose.bom)
  implementation(composeBom)
  androidTestImplementation(composeBom)

  // Core Android dependencies
  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)

  // Arch Components
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.viewmodel.compose)

  // Compose
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.compose.material3)
  implementation("androidx.compose.material:material-icons-core")
  implementation("androidx.compose.material:material-icons-extended")
  // Tooling
  debugImplementation(libs.androidx.compose.ui.tooling)
  // Instrumented tests
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  debugImplementation(libs.androidx.compose.ui.test.manifest)

  // Local tests: jUnit, coroutines, Android runner
  testImplementation(libs.junit)
  // Parse the shipped translation catalogue in local regression tests.
  testImplementation("org.json:json:20240303")
  testImplementation(libs.kotlinx.coroutines.test)

  // Instrumented tests: jUnit rules and runners
  androidTestImplementation(libs.androidx.test.core)
  androidTestImplementation(libs.androidx.test.ext.junit)
  androidTestImplementation(libs.androidx.test.runner)
  androidTestImplementation(libs.androidx.test.espresso.core)

  // Navigation
  implementation(libs.androidx.navigation3.ui)
  implementation(libs.androidx.navigation3.runtime)
  implementation(libs.androidx.lifecycle.viewmodel.navigation3)

  // TensorFlow Lite
  implementation(libs.tensorflow.lite)
  implementation(libs.tensorflow.lite.support)
  implementation("com.microsoft.onnxruntime:onnxruntime-android:1.23.2")
  implementation("com.microsoft.onnxruntime:onnxruntime-extensions-android:0.13.0")
}
