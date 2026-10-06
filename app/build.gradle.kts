plugins { id("com.android.application") }

val abis = listOf("arm64-v8a", "x86_64")
val jniOut = layout.buildDirectory.dir("generated/ancduJni")

val ancduNative by tasks.registering {
    val script = rootProject.file("tools/build-android.sh")
    inputs.dir(rootProject.file("app/src/main/cpp"))
    inputs.file(script)
    outputs.dir(jniOut)
    doLast {
        abis.forEach { abi ->
            providers.exec {
                workingDir = rootProject.projectDir
                commandLine("bash", script.path, abi, jniOut.get().asFile.path)
            }.result.get().assertNormalExitValue()
        }
    }
}

android {
    namespace = "dev.ancdu"
    compileSdk = 34
    ndkVersion = "27.0.12077973"
    defaultConfig {
        applicationId = "dev.ancdu"
        minSdk = 30
        targetSdk = 34
        versionCode = 1
        versionName = "0.1"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += abis }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug") // личное использование
        }
    }
    sourceSets["main"].jniLibs.srcDir(jniOut.get().asFile)
    packaging { jniLibs { useLegacyPackaging = true } }
}

tasks.named("preBuild") { dependsOn(ancduNative) }

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
