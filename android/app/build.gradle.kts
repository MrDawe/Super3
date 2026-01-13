plugins {
  id("com.android.application")
  id("org.jetbrains.kotlin.android")
}

android {
  namespace = "com.izzy2lost.super3"
  compileSdk = 36
  buildToolsVersion = "36.1.0"
  ndkVersion = "29.0.14206865"

  defaultConfig {
    applicationId = "com.izzy2lost.super3"
    minSdk = 26
    targetSdk = 36

    versionCode = 11
    versionName = "1.1.0"

    ndk {
      abiFilters += listOf("arm64-v8a")
    }

    externalNativeBuild {
      cmake {
        cppFlags += listOf("-std=c++17", "-fexceptions", "-frtti")
      }
    }
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(
        getDefaultProguardFile("proguard-android-optimize.txt"),
        "proguard-rules.pro"
      )
    }
  }

  externalNativeBuild {
    cmake {
      path = file("src/main/cpp/CMakeLists.txt")
      version = "3.30.3"
    }
  }

  packaging {
    resources.excludes += setOf(
      "**/*.md",
      "META-INF/LICENSE*",
      "META-INF/NOTICE*"
    )
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
  }
}

dependencies {
  implementation("androidx.core:core-ktx:1.15.0")
  implementation("androidx.appcompat:appcompat:1.7.0")
  implementation("androidx.constraintlayout:constraintlayout:2.1.4")
  implementation("androidx.documentfile:documentfile:1.0.1")
  implementation("androidx.recyclerview:recyclerview:1.4.0")
  implementation("com.google.android.material:material:1.14.0-alpha07")
}
