import com.google.protobuf.gradle.id
import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    id("quality-kit.android")
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.androidx.navigation.safeargs)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.protobuf)
}

/*
'keystore.properties' should look like the following:

storeFile=my.keystore
storePassword=my_store_password
keyAlias=my_key_alias
keyPassword=my_key_password
 */

val keystoreProperties = Properties()
val keystoreFileExists = rootProject.file("keystore.properties").exists()

if (keystoreFileExists) {
    keystoreProperties.load(rootProject.file("keystore.properties").inputStream())
}

android {
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "dev.twenty3p.tubik"
        minSdk = 26
        targetSdk = 36
        versionCode = 87
        versionName = "1.2.3"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        resValue("string", "app_name", "Tubik")
    }

    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("exportSchema", "true")
    }

    viewBinding {
        enable = true
    }

    signingConfigs {
        if (keystoreFileExists) {
            create("release") {
                storeFile = keystoreProperties["storeFile"]?.let { file(it as String) }
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")?.takeIf { it.storeFile != null }
            externalNativeBuild {
                cmake {
                    cppFlags += listOf("-O2", "-DTUBIK_ENFORCE_INTEGRITY=1")
                }
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }

        getByName("debug") {
            isDebuggable = true
            applicationIdSuffix = ".debug"
            resValue("string", "app_name", "Tubik Debug")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_17
            javaParameters = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs.excludes.add("lib/armeabi-v7a/*_neon.so")
    }

    tasks.register("testClasses")

    buildFeatures {
        buildConfig = true
        resValues = true
    }

    dependenciesInfo {
        // Disables dependency metadata when building APKs.
        includeInApk = false
        // Disables dependency metadata when building Android App Bundles.
        includeInBundle = false
    }

    // language preference for Android 13 and above
    androidResources {
        generateLocaleConfig = true
    }

    namespace = "com.github.libretube"
}

dependencies {
    /* Android Core */
    implementation(libs.androidx.activity)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.annotation.experimental)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.appcompat.resources)
    implementation(libs.androidx.cardview)
    implementation(libs.androidx.collection)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.coordinatorlayout)
    implementation(libs.androidx.core)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.documentfile)
    implementation(libs.androidx.drawerlayout)
    implementation(libs.androidx.fragment)
    implementation(libs.androidx.loader)
    implementation(libs.androidx.media)
    implementation(libs.androidx.media3.transformer)
    implementation(libs.androidx.navigation.common)
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.runtime)
    implementation(libs.androidx.navigation.ui)
    implementation(libs.androidx.preference)
    implementation(libs.androidx.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.vectordrawable)
    implementation(libs.androidx.vectordrawable.animated)
    implementation(libs.androidx.viewpager)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.work.runtime)

    /* Android Lifecycle */
    implementation(libs.lifecycle.common)
    implementation(libs.lifecycle.livedata)
    implementation(libs.lifecycle.livedata.core)
    implementation(libs.lifecycle.runtime)
    implementation(libs.lifecycle.service)
    implementation(libs.lifecycle.viewmodel.savedstate)

    /* Design */
    implementation(libs.material)

    /* ExoPlayer */
    implementation(libs.androidx.media3.common)
    implementation(libs.androidx.media3.database)
    implementation(libs.androidx.media3.datasource)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.exoplayer.hls)
    implementation(libs.androidx.media3.exoplayer.dash)
    implementation(libs.androidx.media3.extractor)
    implementation(libs.androidx.media3.session)
    implementation(libs.androidx.media3.ui)

    /* Retrofit, Networking, and Serialization */
    implementation(libs.converter.kotlinx.serialization)
    implementation(libs.google.protobuf.javalite)
    implementation(libs.guava)
    implementation(libs.jspecify)
    implementation(libs.kotlin.parcelize.runtime)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.logging.interceptor)
    implementation(libs.okhttp)
    implementation(libs.okio)
    implementation(libs.square.retrofit)

    /* NewPipe Extractor */
    implementation(libs.newpipeextractor)

    /* Coil */
    coreLibraryDesugaring(libs.desugaring)
    implementation(libs.coil)
    implementation(libs.coil.core)
    implementation(libs.coil.network.core)
    implementation(libs.coil.network.okhttp)

    /* Room */
    ksp(libs.room.compiler)
    implementation(libs.androidx.sqlite)
    implementation(libs.room.common)
    implementation(libs.room.runtime)

    /* Baseline profile generation */
    runtimeOnly(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    /* AndroidX Paging */
    implementation(libs.androidx.paging.common)
    implementation(libs.androidx.paging.runtime)

    /* ONNX Runtime for Music Removal */
    implementation(libs.onnxruntime.android)

    /* Testing */
    testImplementation(libs.junit)
    testImplementation(libs.onnxruntime.jvm)
}

// TODO: exclude from release protobuf
protobuf {
    protoc {
        artifact = libs.protobuf.protoc.get().toString()
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                // TODO: only generate kotlin code
                id("java") {
                    option("lite")
                }
                //                id("kotlin") {
                //                    option("lite")
                //                }
            }
        }
    }
}
