plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

abstract class BuildMobileAssets : Exec() {
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty
}
val mobileAssets = tasks.register<BuildMobileAssets>("buildMobileAssets") {
    outputDirectory.set(layout.buildDirectory.dir("generated/mobileAssets"))
    workingDir(rootDir)
    commandLine("node", "scripts/build-mobile-assets.mjs", layout.buildDirectory.dir("generated/mobileAssets").get().asFile.absolutePath)
    inputs.files(rootProject.fileTree("host-service/src"), rootProject.fileTree("host-service/scripts"),
        rootProject.file("host-service/package-lock.json"), rootProject.file("host-service/package.json"), rootProject.file("host-service/tsconfig.json"),
        rootProject.fileTree("tools/mobile-assets") { exclude("node_modules/**") },
        rootProject.file("scripts/build-mobile-assets.mjs"), rootProject.file("scripts/mobile-install.mjs"), rootProject.file("scripts/mobile-bootstrap.sh"))
}
androidComponents.onVariants { variant ->
    variant.sources.assets?.addGeneratedSourceDirectory(mobileAssets, BuildMobileAssets::outputDirectory)
}

android {
    namespace = "com.worldcopy.agentdeck"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.worldcopy.agentdeck"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    packaging.resources.excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
    androidResources.noCompress += "tgz"
    sourceSets.getByName("androidTest").assets.srcDir("src/test/resources")
}

dependencies {
    implementation(libs.commonmark)
    implementation(libs.commonmark.tables)
    implementation(libs.commonmark.strikethrough)
    implementation(libs.commonmark.autolink)
    implementation(libs.commonmark.tasks)
    implementation(libs.sshj)
    implementation(libs.bouncycastle)
    implementation(libs.bouncycastle.pem)
    implementation(libs.slf4j.nop)
    implementation(libs.okhttp)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
