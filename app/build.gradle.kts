import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
}

val appVersionName = "0.9.0"

fun versionCodeDate(): Int {
    return (System.currentTimeMillis() / 1000).toInt()
}

fun getAppGitHead(): String {
    return try {
        providers.exec {
            commandLine("git", "rev-parse", "HEAD")
            isIgnoreExitValue = true
        }.standardOutput.asText.map { it.trim() }.getOrElse("")
    } catch (_: Exception) {
        ""
    }
}

fun getAppBuildTime(): String {
    return try {
        providers.exec {
            commandLine("git", "log", "-1", "--pretty=%ai")
            isIgnoreExitValue = true
        }.standardOutput.asText.map { it.trim() }.getOrElse("")
    } catch (_: Exception) {
        ""
    }
}

android {
    compileSdk = 36
    namespace = "org.bitfennec.lime"
    ndkVersion = "28.0.13004108"

    defaultConfig {
        applicationId = "org.bitfennec.lime"
        minSdk = 31
        targetSdk = 36
        versionCode = versionCodeDate()
        versionName = appVersionName

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk {
            abiFilters.clear()
            abiFilters.add("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments("-DCMAKE_POLICY_VERSION_MINIMUM=3.5", "-DANDROID_PLATFORM=android-31")
            }
        }

        buildConfigField("String", "versionName", "\"$appVersionName\"")
        buildConfigField("String", "AppCommitHead", "\"${getAppGitHead()}\"")
        buildConfigField("String", "AppBuildTime", "\"${getAppBuildTime()}\"")
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
            excludes += listOf("**/libonnxruntime.so", "**/libonnxruntime4j_jni.so", "**/libsherpa-onnx-jni.so")
        }
    }

    signingConfigs {
        create("release") {
            val keystorePropertiesFile = rootProject.file("keystore/keystore.properties")
            val keystoreProperties = Properties()
            if (keystorePropertiesFile.exists()) {
                FileInputStream(keystorePropertiesFile).use { keystoreProperties.load(it) }
            }
            val keystoreFilePath = System.getenv("RELEASE_STORE_FILE") ?: keystoreProperties.getProperty("storeFile")
            val keystorePassword = System.getenv("RELEASE_STORE_PASSWORD") ?: keystoreProperties.getProperty("storePassword")
            val keyAliasName = System.getenv("RELEASE_KEY_ALIAS") ?: keystoreProperties.getProperty("keyAlias")
            val keyPasswordValue = System.getenv("RELEASE_KEY_PASSWORD") ?: keystoreProperties.getProperty("keyPassword")
            if (!keystoreFilePath.isNullOrEmpty()) {
                val keystoreFile = rootProject.file(keystoreFilePath)
                if (keystoreFile.exists() && !keystorePassword.isNullOrEmpty() && !keyAliasName.isNullOrEmpty() && !keyPasswordValue.isNullOrEmpty()) {
                    storeFile = keystoreFile
                    storePassword = keystorePassword
                    keyAlias = keyAliasName
                    keyPassword = keyPasswordValue
                    enableV1Signing = true
                    enableV2Signing = true
                }
            }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        resValues = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            resValue("string", "app_name", "@string/ime_app_name")
            resValue("drawable", "app_icon", "@mipmap/ic_launcher")
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
        }

        debug {
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "@string/ime_app_name_debug")
            resValue("drawable", "app_icon", "@mipmap/ic_launcher")
            val releaseSigning = signingConfigs.getByName("release")
            if (releaseSigning.storeFile != null) {
                signingConfig = releaseSigning
            }
        }

        // initWith(release) keeps the app_name and app_icon resValue aliases.
        // The baseline profile plugin forces nonMinifiedRelease to stay unminified.
        create("nonMinifiedRelease") {
            initWith(buildTypes.getByName("release"))
            matchingFallbacks += listOf("release")
            isMinifyEnabled = false
            isShrinkResources = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    lint {
        abortOnError = true
        // Pins in gradle/libs.versions.toml are intentional. These checks only
        // report that a newer release exists.
        // https://googlesamples.github.io/android-custom-lint-rules/checks/GradleDependency.md.html
        disable += setOf(
            "GradleDependency",
            "NewerVersionAvailable",
            "AndroidGradlePluginVersion",
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        // Stable across builds and above legacy timestamp codes to allow in-place updates.
        variant.outputs.forEach { it.versionCode.set(2_000_000_000) }
    }
}

dependencies {
    // Explicit vendored pure-Java classes jar (native .so files are stripped and downloaded on-demand)
    implementation(files("libs/sherpa-onnx-1.13.7.jar"))
    implementation(libs.appcompat)
    implementation(libs.constraintlayout)
    implementation(libs.material)
    implementation(libs.recyclerview)
    implementation(libs.google.flexbox)
    implementation(libs.emoji2)
    implementation(libs.emoji2.views)
    implementation(libs.navigation.fragment.ktx)
    implementation(libs.navigation.ui.ktx)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.preference.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.activity.ktx)
    implementation(libs.fragment.ktx)
    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)
    implementation(libs.onnxruntime.android)
    implementation(libs.profileinstaller)
    baselineProfile(project(":baselineprofile"))
    implementation(libs.image.cropper)
    testImplementation(libs.junit4)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
