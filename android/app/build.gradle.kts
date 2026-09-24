import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    id("com.jaredsburrows.license")
}

// ─────────────────────────────────────────────────────────────────────────────
// Release signing.
//
// A release must never carry the debug key. AGP generates that key per machine and per CI run, so
// two "releases" are signed by two different identities: Android refuses to install one over the
// other (INSTALL_FAILED_UPDATE_INCOMPATIBLE — which costs the user every server, subscription and
// their session), the debug key's private half is public knowledge, and Play rejects a debug-signed
// upload outright.
//
// The real key is read from the environment (CI secrets) or from an untracked keystore.properties
// next to the build. When neither is present the release is left UNSIGNED and says so in its
// filename, because an artefact that cannot be shipped must not look shippable. Passing
// `-PdebugSignedRelease=true` is the one deliberate way back to a debug-signed release for local
// testing, and it labels the output too.
// ─────────────────────────────────────────────────────────────────────────────
val keystoreProperties = Properties().apply {
    val propsFile = rootProject.file("keystore.properties")
    if (propsFile.exists()) propsFile.inputStream().use { load(it) }
}

fun signingSecret(envName: String, propertyName: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }

val releaseStorePath: String =
    signingSecret("DEPARTAMENT_KEYSTORE", "storeFile") ?: "$rootDir/keystore/release.jks"
val releaseStorePassword: String? = signingSecret("DEPARTAMENT_KEYSTORE_PASSWORD", "storePassword")
val releaseKeyAlias: String? = signingSecret("DEPARTAMENT_KEY_ALIAS", "keyAlias")
val releaseKeyPassword: String? = signingSecret("DEPARTAMENT_KEY_PASSWORD", "keyPassword")

val hasReleaseKey: Boolean = file(releaseStorePath).exists() &&
    releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null

val debugSignedRelease: Boolean =
    (project.findProperty("debugSignedRelease") as String?)?.toBoolean() ?: false

// Said once, at configuration time, only when a release task was actually asked for — a build that
// produces an uninstallable artefact should not be quiet about it.
if (!hasReleaseKey && gradle.startParameter.taskNames.any { it.contains("elease") }) {
    if (debugSignedRelease) {
        logger.lifecycle(
            "departament: release will be DEBUG-SIGNED (-PdebugSignedRelease). Test builds only — " +
                "the debug key differs per machine and per CI run, so this APK cannot be upgraded " +
                "by, or upgrade to, any other build. Outputs are tagged -debugsigned."
        )
    } else {
        logger.lifecycle(
            "departament: no release keystore, so the release will be UNSIGNED and tagged " +
                "-unsigned. Supply DEPARTAMENT_KEYSTORE / DEPARTAMENT_KEYSTORE_PASSWORD / " +
                "DEPARTAMENT_KEY_ALIAS / DEPARTAMENT_KEY_PASSWORD (or keystore.properties with " +
                "storeFile / storePassword / keyAlias / keyPassword), or pass " +
                "-PdebugSignedRelease=true for a throwaway installable test build."
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Номер версии — общий с программой для ПК.
//
// Выпуск (.github/workflows/release.yml) собирает обе программы под одним номером X.Y.Z и передаёт
// его сюда: -PappVersionName=1.0.5. Приложение сравнивает номер выпуска со своим versionName, а
// Android ставит обновление, только если versionCode вырос, — поэтому код считается из того же
// номера: X·100000 + Y·1000 + Z·10 + 9. У предварительного X.Y.Z-rc.N последняя цифра — N (до 8):
// rc всегда ниже своего выпуска и выше предыдущего.
//
// Тестовая сборка после выпуска X получает номер «X-dev» и код на единицу больше, чем у X: её можно
// поставить поверх выпуска X, а следующий выпуск встанет поверх неё. Локальная сборка без номера —
// 0.0.0-dev.
// ─────────────────────────────────────────────────────────────────────────────
val appVersionName: String =
    (project.findProperty("appVersionName") as String?)?.trim()?.removePrefix("v")?.takeIf { it.isNotEmpty() }
        ?: "0.0.0-dev"

fun versionCodeOf(version: String): Int {
    val m = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-rc\.(\d+))?(-dev)?$""").matchEntire(version)
        ?: throw GradleException("departament: версия «$version» не подходит, нужна вида 1.2.3, 1.2.3-rc.1 или 1.2.3-dev")
    val major = m.groupValues[1].toInt()
    val minor = m.groupValues[2].toInt()
    val patch = m.groupValues[3].toInt()
    val rc = m.groupValues[4].takeIf { it.isNotEmpty() }?.toInt()
    val dev = m.groupValues[5].isNotEmpty()
    if (major !in 0..9 || minor !in 0..99 || patch !in 0..99) {
        throw GradleException("departament: версия «$version» не укладывается в versionCode (X до 9, Y и Z до 99)")
    }
    val last = if (rc == null) 9 else rc.coerceIn(0, 8)
    return major * 100000 + minor * 1000 + patch * 10 + last + (if (dev) 1 else 0)
}

android {
    namespace = "com.v2ray.ang"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "com.departamentvpn.app"
        minSdk = 24
        targetSdk = 37
        versionCode = versionCodeOf(appVersionName)
        versionName = appVersionName
        multiDexEnabled = true

        val abiFilterList = (properties["ABI_FILTERS"] as? String)?.split(';')
        splits {
            abi {
                isEnable = true
                reset()
                if (abiFilterList != null && abiFilterList.isNotEmpty()) {
                    include(*abiFilterList.toTypedArray())
                } else {
                    include(
                        "arm64-v8a",
                        "armeabi-v7a",
                        "x86_64",
                        "x86"
                    )
                }
                isUniversalApk = abiFilterList.isNullOrEmpty()
            }
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Departament VPN backend / Telegram auth configuration.
        // Leave BACKEND_BASE_URL blank to keep login OPTIONAL (app stays fully usable offline).
        // Fill these in (or override per build type/flavor) when the real bot backend lands.
        buildConfigField("String", "BACKEND_BASE_URL", "\"https://web.departament.site/api\"")
        buildConfigField("String", "BOT_USERNAME", "\"departamentvpnbot\"")
        // User-Agent for subscription fetches and backend calls. The panel picks the subscription
        // format (XRAY_JSON template vs base64 link list) from this header using ITS OWN
        // client->template mapping, so the string that yields the template is operator-specific:
        // this field is that operator's knob and is sent verbatim (BackendConfig only refuses a
        // value that cannot travel in an HTTP header).
        // Blank = the app's own default, "v2rayNG/<versionName>" — the client string every panel
        // recognises as this client, answered with the base64 link list, which the app parses and
        // which is what ships today. Fill this in with the client string this deployment's
        // Remnawave maps to xray-json to negotiate the operator's routing/DNS template.
        // Do NOT put branding here: "DepartamentVPN/1.0" (what earlier builds shipped) is an
        // unknown client to every panel, so it gets the base64 list anyway AND names the
        // deployment on every request.
        buildConfigField("String", "SUB_USER_AGENT", "\"\"")
    }

    signingConfigs {
        // Declared only when the material to fill it actually exists; see the block above the
        // `plugins` declaration for where the values come from.
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(releaseStorePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    buildTypes {
        release {
            // Safe only because proguard-rules.pro exists: the Xray config's Gson field names are
            // the wire format, and the libv2ray/hev-socks5-tunnel JNI surfaces are resolved by
            // literal name. Read that file before changing this flag.
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Never the debug key by default. No key at all beats a key that makes an
            // unshippable build look shippable.
            signingConfig = if (hasReleaseKey) {
                signingConfigs.getByName("release")
            } else if (debugSignedRelease) {
                signingConfigs.getByName("debug")
            } else {
                null
            }
        }
    }

    flavorDimensions.add("distribution")
    productFlavors {
        create("fdroid") {
            dimension = "distribution"
            applicationIdSuffix = ".fdroid"
            buildConfigField("String", "DISTRIBUTION", "\"F-Droid\"")
        }
        create("playstore") {
            dimension = "distribution"
            buildConfigField("String", "DISTRIBUTION", "\"Play Store\"")
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    applicationVariants.all {
        val variant = this
        val isFdroid = variant.productFlavors.any { it.name == "fdroid" }
        // What the file is, in the file's own name. A debug artefact and a release artefact used to
        // be called exactly the same thing, and a release with no key looked identical to one with.
        val artefactMarker = when {
            variant.buildType.name != "release" -> "-${variant.buildType.name}"
            hasReleaseKey -> ""
            debugSignedRelease -> "-debugsigned"
            else -> "-unsigned"
        }
        if (isFdroid) {
            // Same 64-before-32 rule as the playstore branch below, and the same inversion to
            // undo — here the rank is the tiebreaker in the low digits rather than the leading
            // one. Both corrections RAISE a rank and never lower one, so no already-published
            // fdroid APK is downgraded. Inert for our CI today, which only ever builds playstore.
            val versionCodes =
                mapOf(
                    "armeabi-v7a" to 2, "arm64-v8a" to 3, "x86" to 4, "x86_64" to 5, "universal" to 0
                )

            variant.outputs
                .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
                .forEach { output ->
                    val abi = output.getFilter("ABI") ?: "universal"
                    output.outputFileName =
                        "departament_${variant.versionName}-fdroid_${abi}${artefactMarker}.apk"
                    if (versionCodes.containsKey(abi)) {
                        output.versionCodeOverride =
                            (100 * variant.versionCode + versionCodes[abi]!!).plus(5000000)
                    } else {
                        return@forEach
                    }
                }
        } else {
            // One rank per ABI, distinct. Every entry used to be 4, so all five splits resolved to
            // 4000731 and Play refuses the second APK of a multi-APK release ("Version code
            // 4000731 has already been used"). `universal` stays lowest so a device-specific split
            // always outranks the fat one on the same device.
            //
            // THE RANKS START AT 4, AND THAT FLOOR IS NOT COSMETIC. This value is the LEADING digit
            // of the shipped versionCode, so lowering a rank lowers the version. Every playstore
            // APK this project has ever produced used rank 4 — 4000731 on every ABI — and giving
            // arm64-v8a rank 1 made the next CI build 1000731, three million BELOW what testers
            // already had installed. Android refuses that install outright
            // (INSTALL_FAILED_VERSION_DOWNGRADE, surfaced as «Приложение не установлено»), so the
            // build that fixed the duplicate-code defect also made itself uninstallable for
            // everyone who had the previous one. Ranks may be reordered; they may never go below
            // the highest rank already published, which is 4.
            //
            // THE ORDER WITHIN EACH ABI FAMILY IS LOAD-BEARING TOO. Play serves the highest
            // versionCode the device can run, and a 64-bit device can run its 32-bit sibling. So
            // arm64-v8a must outrank armeabi-v7a, and x86_64 must outrank x86, or every 64-bit
            // device is served the 32-bit split — on a store that mandates 64-bit support. Both
            // pairs were inverted; CI builds one ABI at a time, so it never showed.
            val versionCodes =
                mapOf("universal" to 4, "armeabi-v7a" to 5, "arm64-v8a" to 6, "x86" to 7, "x86_64" to 8)

            variant.outputs
                .map { it as com.android.build.gradle.internal.api.ApkVariantOutputImpl }
                .forEach { output ->
                    val abi = if (output.getFilter("ABI") != null)
                        output.getFilter("ABI")
                    else
                        "universal"

                    output.outputFileName =
                        "departament_${variant.versionName}_${abi}${artefactMarker}.apk"
                    if (versionCodes.containsKey(abi)) {
                        output.versionCodeOverride =
                            (1000000 * versionCodes[abi]!!).plus(variant.versionCode)
                    } else {
                        return@forEach
                    }
                }
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

}

dependencies {
    // Core Libraries
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar", "*.jar"))))

    // AndroidX Core Libraries
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.preference.ktx)
    implementation(libs.recyclerview)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.viewpager2)
    implementation(libs.androidx.fragment)

    // Custom Tabs (Telegram deep-link / payment checkout hand-off)
    implementation("androidx.browser:browser:1.8.0")

    // UI Libraries
    implementation(libs.material)
    // NO `toasty`. It was upstream's notification layer — the green tick, the red cross, the
    // system-chrome capsule floating over the screen — and the owner asked for the layer itself
    // rather than for any one message: «это же старые от в2рей уведомления… их убрать надо
    // совсем». `NoticePolicy` / `Notice` replaced it, on one themed bottom surface, and the
    // dependency comes out with it so an upstream merge that adds a `Toasty.error(...)` call
    // fails to compile instead of quietly putting the layer back.
    implementation(libs.editorkit)
    implementation(libs.flexbox)

    // Data and Storage Libraries
    implementation(libs.mmkv.static)
    implementation(libs.gson)
    implementation(libs.okhttp)

    // Reactive and Utility Libraries
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.core)

    // Language and Processing Libraries
    implementation(libs.language.base)
    implementation(libs.language.json)

    // Intent and Utility Libraries
    implementation(libs.quickie.foss)
    implementation(libs.core)

    // AndroidX Lifecycle and Architecture Components
    implementation(libs.lifecycle.viewmodel.ktx)
    implementation(libs.lifecycle.livedata.ktx)
    implementation(libs.lifecycle.runtime.ktx)

    // Background Task Libraries
    implementation(libs.work.runtime.ktx)
    implementation(libs.work.multiprocess)

    // Multidex Support
    implementation(libs.multidex)

    // Testing Libraries
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    testImplementation(libs.org.mockito.mockito.inline)
    testImplementation(libs.mockito.kotlin)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
}
