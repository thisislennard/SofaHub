import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Release-Signatur: lokal aus keystore.properties (nicht im Repo), in GitHub Actions aus Umgebungsvariablen.
// Ohne beides entsteht eine unsignierte Release-APK; der Release-Workflow bricht dann ab.
composeCompiler {
    stabilityConfigurationFiles.add(project.layout.projectDirectory.file("compose-stability.conf"))
}

val keystoreProps = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
fun signingValue(prop: String, env: String): String? =
    keystoreProps.getProperty(prop) ?: System.getenv(env)?.takeIf { it.isNotBlank() }

val appVersionName = providers.gradleProperty("sofahub.versionName").get()
val appVersionCode = providers.gradleProperty("sofahub.versionCode").get().toInt()
val updateRepo = providers.gradleProperty("sofahub.updateRepo").get()
require(Regex("^[A-Za-z0-9-]+/[A-Za-z0-9._-]+$").matches(updateRepo)) { "sofahub.updateRepo muss owner/repo sein" }

val robolectricRuntime: Configuration = configurations.create("robolectricRuntime")
val robolectricDepsDir = layout.buildDirectory.dir("robolectric-deps")
val syncRobolectricDeps = tasks.register<Sync>("syncRobolectricDeps") {
    from(robolectricRuntime)
    into(robolectricDepsDir)
}

android {
    namespace = "de.sofahub.app"
    compileSdk = 37

    defaultConfig {
        applicationId = "de.sofahub.app"
        // Fire OS 7 = API 28, Fire OS 8 = API 30 (gemessen am Stick: API 30). minSdk 28 hält ältere Sticks lauffähig.
        minSdk = 28
        targetSdk = 37
        versionCode = appVersionCode
        versionName = appVersionName
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        buildConfigField("String", "RELEASE_APPLICATION_ID", "\"de.sofahub.app\"")
        manifestPlaceholders["appLabel"] = "Sofa Hub"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        val storeFile = signingValue("storeFile", "SOFAHUB_KEYSTORE_FILE")
        if (storeFile != null) {
            create("release") {
                this.storeFile = file(storeFile)
                storePassword = signingValue("storePassword", "SOFAHUB_KEYSTORE_PASSWORD")
                keyAlias = signingValue("keyAlias", "SOFAHUB_KEY_ALIAS")
                keyPassword = signingValue("keyPassword", "SOFAHUB_KEY_PASSWORD")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        debug {
            // Eigene Kennung: Debug- und Release-Version liegen nebeneinander und überschreiben sich nie
            // (unterschiedliche Signaturen würden sonst eine Deinstallation mit Datenverlust erzwingen).
            applicationIdSuffix = ".debug"
            manifestPlaceholders["appLabel"] = "Sofa Hub Debug"
        }
        release {
            signingConfig = signingConfigs.findByName("release")
            // Nur für einmalige Datenübernahme per `run-as` (-Psofahub.debuggableRelease); nie veröffentlichen.
            isDebuggable = providers.gradleProperty("sofahub.debuggableRelease").isPresent
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Robolectric offline mit dem von Gradle geladenen Android-11-Abbild (= API 30 des Sticks), statt eines
            // eigenen Downloads zur Laufzeit, der auf CI-Runnern unzuverlässig war.
            all {
                it.dependsOn(syncRobolectricDeps)
                it.systemProperty("robolectric.offline", "true")
                it.systemProperty("robolectric.dependency.dir", robolectricDepsDir.get().asFile.absolutePath)
            }
        }
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }

    packaging {
        resources.excludes += setOf("META-INF/*.version", "META-INF/LICENSE*", "META-INF/NOTICE*", "DebugProbesKt.bin")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.process)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.androidx.datastore)
    implementation(libs.androidx.exifinterface)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    robolectricRuntime(libs.robolectric.android11)
}
