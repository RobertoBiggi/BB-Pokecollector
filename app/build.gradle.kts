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
        // In CI versionCode = number of commits on main and versionName = "<appVersion>.<versionCode>",
        // so every release gets an increasing number without manual edits (see .github/workflows/android.yml).
        val buildNumber = providers.gradleProperty("versionCode").orNull?.toIntOrNull() ?: 1
        versionCode = buildNumber
        versionName = "${providers.gradleProperty("appVersion").get()}.$buildNumber"
        // UI languages: English (base) and Italian. Drops the libraries' translations in other languages.
        resourceConfigurations += listOf("en", "it")
        // Only used by the screenshots of the README (app/src/androidTest, workflow screenshots.yml).
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Debug keystore fixed in the repo, with public passwords: test builds signed this way update
        // each other (same id and same signature on every build), but cannot replace the release signed with the real key.
        getByName("debug") {
            storeFile = file("signing/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Release key: comes only from GitHub Actions secrets, never from the repo.
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
        release {
            // Without secrets (local builds, pull requests) the release APK stays unsigned.
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
        // Warnings stop the build too: fix them, or silence one case with tools:ignore / @Suppress and a reason.
        warningsAsErrors = true
        checkReleaseBuilds = false
        // Dependency versions are pinned on purpose (see gradle/libs.versions.toml).
        disable += setOf("GradleDependency", "AndroidGradlePluginVersion", "NewerVersionAvailable", "OldTargetApi")
        // Text report with every issue, printed in the build output (so the CI log shows them).
        textReport = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

}

// The card database (cards.sqlite) goes into the APK as an asset, so the app works right away without downloading it. It is not in the repo:
// it is taken from -PcatalogDb=<file>, from CATALOG_DB (in CI the "catalog" release downloads it) or from the generator repository next to this one.
// Without the file the APK still builds and the database is downloaded from Settings.
abstract class PrepareCatalogAsset : DefaultTask() {
    @get:Input
    abstract val source: Property<String>

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun copy() {
        val from = File(source.get())
        val dir = outputDir.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        if (from.isFile) from.copyTo(File(dir, "cards.sqlite")) else logger.warn("Database delle carte non trovato ($from): l'APK non lo include.")
    }
}

val prepareCatalogAsset = tasks.register<PrepareCatalogAsset>("prepareCatalogAsset") {
    source.set(
        providers.gradleProperty("catalogDb").orElse(providers.environmentVariable("CATALOG_DB"))
            .orElse(rootDir.resolve("../Card-collector-db-gen/out/cards.sqlite").path),
    )
    // The content of the file can change without its path changing: it is copied again on every build (23 MB).
    outputs.upToDateWhen { false }
}

androidComponents {
    onVariants { variant -> variant.sources.assets?.addGeneratedSourceDirectory(prepareCatalogAsset, PrepareCatalogAsset::outputDir) }
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
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)

    testImplementation(libs.junit4)
    testImplementation(libs.sqlite.jdbc)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
