import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Release signing config is read from keystore.properties at the repo root
// (git-ignored). When the file is absent (e.g. fresh clone / CI without
// secrets) the release build is simply left unsigned instead of failing.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) FileInputStream(keystorePropsFile).use { load(it) }
}

android {
    namespace = "com.localkeys.android"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.localkeys.android"
        minSdk = 28
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                // A keystore é PKCS12 (formato que o keytool moderno também gera).
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Auto-sign when keystore.properties is present; otherwise unsigned.
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets["main"].kotlin.srcDirs("src/main/kotlin")
    sourceSets["test"].kotlin.srcDirs("src/test/kotlin")

    testOptions {
        unitTests {
            // Os testes JVM usam org.json (real, do Maven) e o lazysodium-java,
            // que carrega o libsodium nativo via resource-loader — nada de stubs.
            isReturnDefaultValues = false
            isIncludeAndroidResources = false
            // stdout/stderr dos testes aparecem no log do CI (essencial p/ depurar).
            all { it.testLogging.showStandardStreams = true }
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // bcprov-jdk18on e o transitivo jspecify trazem o mesmo manifest OSGi
            // multi-release; conteúdo irrelevante em runtime, só duplica no merge.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    // ── AndroidX core ────────────────────────────────────────────────────
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.core:core-splashscreen:1.0.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    // Material XML themes (Theme.Material3.DayNight) — necessárias para o tema
    // base/splash definidos em res/values/themes.xml.
    implementation("com.google.android.material:material:1.12.0")

    // ── Compose ──────────────────────────────────────────────────────────
    val composeBom = platform("androidx.compose:compose-bom:2024.09.02")
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    // ── Cripto: libsodium (Argon2id + XChaCha20-Poly1305) ────────────────
    // lazysodium-android traz o libsodium nativo para todos os ABIs; o @aar do
    // JNA é o que entrega as libs nativas do JNA no Android (ver README do
    // lazysodium-android). Excluímos o jna.jar transitivo do POM — senão o
    // checkDebugDuplicateClasses acusa classes do com.sun.jna repetidas. O MESMO
    // código roda em teste JVM com lazysodium-java.
    implementation("com.goterl:lazysodium-android:5.2.0") {
        exclude(group = "net.java.dev.jna", module = "jna")
    }
    implementation("net.java.dev.jna:jna:5.17.0@aar")

    // ── Biometria (desbloqueio sem senha) ────────────────────────────────
    implementation("androidx.biometric:biometric:1.1.0")

    // ── Import: Argon2id do Bitwarden (export cifrado) ───────────────────
    // A JVM/Android não tem Argon2 no JCE; o BouncyCastle é a implementação
    // auditada. Usado só como classe (nunca registrado como provider do SO).
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // ── SAF (abrir/criar o vault .tkeys no armazenamento do usuário) ─────
    implementation("androidx.documentfile:documentfile:1.0.1")

    // ── Persistência de preferências (URI do vault, opt-ins) ─────────────
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // ── Kotlin coroutines ────────────────────────────────────────────────
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ── Testes (JVM) ─────────────────────────────────────────────────────
    // A lógica pura — header/cripto do .tkeys, TOTP, model do vault — roda sem
    // Android. org.json vem do Maven porque o do SDK é um stub que lança em
    // teste unitário. lazysodium-java fornece o libsodium nativo para a JVM.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("com.goterl:lazysodium-java:5.1.4")
    // BouncyCastle roda puro na JVM (mesmo código do Argon2id do app).
    testImplementation("org.bouncycastle:bcprov-jdk18on:1.78.1")

    // ── Debug / preview ──────────────────────────────────────────────────
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
