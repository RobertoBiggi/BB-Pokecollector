import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.robertobiggi.cardcollection"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.robertobiggi.cardcollection"
        minSdk = 26
        targetSdk = 35
        // In CI versionCode = numero di commit di main e versionName = "<appVersion>.<versionCode>",
        // così ogni release ha un numero crescente senza modifiche a mano (vedi .github/workflows/android.yml).
        val buildNumber = providers.gradleProperty("versionCode").orNull?.toIntOrNull() ?: 1
        versionCode = buildNumber
        versionName = "${providers.gradleProperty("appVersion").get()}.$buildNumber"
        // Lingue dell'interfaccia: inglese (base) e italiano. Scarta le traduzioni delle librerie in altre lingue.
        resourceConfigurations += listOf("en", "it")
    }

    signingConfigs {
        // Keystore di debug fisso nel repo, con password pubbliche: serve solo alle build di prova,
        // che hanno un id diverso (".debug") e quindi non possono mai sostituire l'app vera.
        getByName("debug") {
            storeFile = file("signing/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Chiave di release: arriva solo dai secret di GitHub Actions, mai dal repo.
        val releaseKeystore = System.getenv("RELEASE_KEYSTORE_PATH")
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            // Senza secret (build locali, pull request) l'APK release resta non firmato.
            signingConfig = signingConfigs.findByName("release")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        // Le versioni delle dipendenze sono fissate di proposito (vedi gradle/libs.versions.toml).
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "OldTargetApi")
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)

    testImplementation(libs.junit4)
    testImplementation(libs.sqlite.jdbc)
}
