import java.util.Properties

plugins { id("com.android.application") }

// Release-подпись: ANCDU_SIGNING (путь к .properties: storeFile, storePassword, keyAlias,
// keyPassword), по умолчанию ~/.android/ancdu-release.properties; либо переменные
// ANCDU_KEYSTORE / ANCDU_KEYSTORE_PASSWORD / ANCDU_KEY_ALIAS / ANCDU_KEY_PASSWORD.
// Без них release подписывается debug-ключом (для локальной проверки, не для публикации).
val signing: Map<String, String>? = run {
    val env = System.getenv()
    env["ANCDU_KEYSTORE"]?.let { ks ->
        return@run mapOf(
            "storeFile" to ks,
            "storePassword" to env["ANCDU_KEYSTORE_PASSWORD"].orEmpty(),
            "keyAlias" to (env["ANCDU_KEY_ALIAS"] ?: "ancdu"),
            "keyPassword" to (env["ANCDU_KEY_PASSWORD"] ?: env["ANCDU_KEYSTORE_PASSWORD"].orEmpty()))
    }
    val f = file(env["ANCDU_SIGNING"] ?: "${System.getProperty("user.home")}/.android/ancdu-release.properties")
    if (!f.isFile) return@run null
    val p = Properties().apply { f.inputStream().use { load(it) } }
    p.stringPropertyNames().associateWith { p.getProperty(it) }
}

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
        versionCode = 110
        versionName = "1.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        ndk { abiFilters += abis }
    }
    signingConfigs {
        if (signing != null) create("release") {
            storeFile = file(signing.getValue("storeFile"))
            storePassword = signing.getValue("storePassword")
            keyAlias = signing.getValue("keyAlias")
            keyPassword = signing.getValue("keyPassword")
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug").also {
                logger.warn("ancdu: release-ключ не найден — release подписан debug-ключом")
            }
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
