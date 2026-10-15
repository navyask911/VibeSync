import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.roborazzi)
  alias(libs.plugins.secrets)
  alias(libs.plugins.google.services)
}

// Truecaller Client ID is public OAuth client configuration (not the partner secret).
// Supply it through VIBESYNC_TRUECALLER_CLIENT_ID or -PVIBESYNC_TRUECALLER_CLIENT_ID.
val truecallerClientId = providers.environmentVariable("VIBESYNC_TRUECALLER_CLIENT_ID")
  .orElse(providers.gradleProperty("VIBESYNC_TRUECALLER_CLIENT_ID"))
  .orElse("UNCONFIGURED_TRUECALLER_CLIENT_ID")
  .get()
val escapedTruecallerClientId = truecallerClientId
  .replace("\\", "\\\\")
  .replace("\"", "\\\"")

android {
  namespace = "com.example"
  compileSdk = 36

  defaultConfig {
    applicationId = "com.aistudio.freedating.mkwvxq"
    minSdk = 24
    targetSdk = 35
    versionCode = 5
    versionName = "1.2.0"

    buildConfigField("String", "TRUECALLER_CLIENT_ID", "\"$escapedTruecallerClientId\"")
    manifestPlaceholders["truecallerClientId"] = truecallerClientId

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    // Release credentials are supplied only through the CI/local process environment.
    // Never read signing passwords from tracked Gradle properties or a committed properties file.
    val releaseKeystorePath = System.getenv("KEYSTORE_PATH")
    val releaseStorePassword = System.getenv("KEYSTORE_PASSWORD")
    val releaseKeyPassword = System.getenv("KEY_PASSWORD")
    val releaseKeyAlias = System.getenv("KEY_ALIAS")
    if (!releaseKeystorePath.isNullOrBlank() &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank()) {
      create("release") {
        storeFile = file(releaseKeystorePath)
        storePassword = releaseStorePassword
        keyAlias = releaseKeyAlias
        keyPassword = releaseKeyPassword
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = true
      isShrinkResources = true
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
      if (signingConfigs.names.contains("release")) signingConfig = signingConfigs.getByName("release")
    }
    debug { signingConfig = signingConfigs.getByName("debug") }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }

  buildFeatures {
    compose = true
    buildConfig = true
  }

  packaging {
    jniLibs {
      // Keep all dependency-provided ABIs; compress JNI libraries in the APK.
      useLegacyPackaging = true
      keepDebugSymbols.clear()
    }
    resources {
      excludes += setOf(
        "META-INF/DEPENDENCIES",
        "META-INF/INDEX.LIST",
        "META-INF/io.netty.versions.properties",
        "META-INF/*.kotlin_module",
        "META-INF/LICENSE*",
        "META-INF/NOTICE*"
      )
    }
  }

  testOptions {
    unitTests {
      isIncludeAndroidResources = true
    }
  }

  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

// Force Kotlin compilation and all JVM tests to run with headless AWT settings.
// The Compose compiler compatibility flag is retained explicitly for CI builds.
tasks.withType<KotlinCompile>().configureEach {
  compilerOptions.freeCompilerArgs.addAll(
    "-P",
    "plugin:androidx.compose.compiler.plugins.kotlin:suppressKotlinVersionCompatibilityCheck=true"
  )
}

tasks.withType<Test>().configureEach {
  systemProperty("java.awt.headless", "true")
  jvmArgs("-Djava.awt.headless=true")
}

// Configure the Secrets Gradle Plugin to use .env and .env.example files
// to match the convention used in Web projects.
secrets {
  propertiesFileName = ".env"
  defaultPropertiesFileName = ".env.example"
  ignoreList.add("FIREBASE_APPCHECK_DEBUG_TOKEN")
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  val roomVersion = "2.7.0"
  implementation("com.razorpay:checkout:1.6.41")

  implementation(platform(libs.androidx.compose.bom))
  implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  implementation(libs.androidx.biometric)
  implementation(libs.androidx.camera.camera2)
  implementation(libs.androidx.camera.core)
  implementation(libs.androidx.camera.lifecycle)
  implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  implementation("androidx.emoji2:emoji2:1.5.0")
  implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  implementation(libs.androidx.navigation.compose)
  implementation("androidx.room:room-runtime:$roomVersion")
  implementation("androidx.room:room-ktx:$roomVersion")
  implementation("net.zetetic:sqlcipher-android:4.19.1@aar")
  implementation("androidx.sqlite:sqlite:2.7.0")
  implementation("androidx.work:work-runtime-ktx:2.9.0")
  implementation("com.google.apis:google-api-services-drive:v3-rev20240123-2.0.0") {
    exclude(group = "org.apache.httpcomponents")
  }
  implementation("com.google.api-client:google-api-client-android:2.2.0")
  implementation("com.dafruits:webrtc:120.0.0")
  implementation(libs.coil.compose)
  implementation(libs.converter.moshi)
  implementation(libs.firebase.ai)
  // Firestore, Realtime Database, Cloud Storage & FCM:
  implementation(libs.firebase.firestore)
  implementation(libs.firebase.storage)
  implementation(libs.firebase.database)
  implementation(libs.firebase.messaging)

  // implementation(libs.firebase.analytics)
  implementation(libs.firebase.appcheck.recaptcha)
  implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.coroutines.play.services)
  implementation(libs.logging.interceptor)
  implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.play.services.location)
  implementation(libs.play.services.auth)
  implementation("com.google.mlkit:face-detection:16.1.7")
  implementation("com.google.zxing:core:3.5.3")
  implementation("androidx.print:print:1.0.0")
  implementation("com.google.crypto.tink:tink-android:1.15.0")
  implementation("com.truecaller.android.sdk:truecaller-sdk:3.2.1")
  implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  testImplementation(libs.roborazzi)
  testImplementation(libs.roborazzi.compose)
  testImplementation(libs.roborazzi.junit.rule)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  ksp("androidx.room:room-compiler:$roomVersion")
  ksp(libs.moshi.kotlin.codegen)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
}
