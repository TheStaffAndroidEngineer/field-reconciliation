plugins { alias(libs.plugins.androidApplication); alias(libs.plugins.kotlinCompose) }
android {
    namespace = "staff.android.field"
    compileSdk { version = release(37) { minorApiLevel = 1 } }
    buildToolsVersion = "37.0.0"
    defaultConfig {
        applicationId = "staff.android.field"; minSdk = 26; targetSdk = 37
        versionCode = 2801; versionName = "28.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes { release {
        optimization { enable = true }; signingConfig = signingConfigs.getByName("debug")
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    } }
    buildFeatures { compose = true; buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_26; targetCompatibility = JavaVersion.VERSION_26 }
}
kotlin { jvmToolchain(27); compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_26) } }
dependencies {
    implementation(project(":field-core"))
    implementation(libs.serializationJson); implementation(libs.okhttp); implementation(libs.workRuntime)
    implementation(libs.androidxLifecycleRuntimeCompose); implementation(libs.coroutinesAndroid)
    implementation(libs.androidxLifecycleViewModel); implementation(libs.androidxActivityCompose)
    implementation(platform(libs.composeBom)); implementation(libs.composeUi)
    implementation(libs.composeFoundation); implementation(libs.composeMaterial3)
    androidTestImplementation(platform(libs.composeBom)); androidTestImplementation(libs.composeTestJunit4)
    androidTestImplementation(libs.androidxTestJunit); androidTestImplementation(libs.androidxTestRunner)
    androidTestImplementation(libs.espressoCore); androidTestImplementation(libs.workTesting)
}
