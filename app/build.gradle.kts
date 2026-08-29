import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.tunombre.tvbridge"
    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.tunombre.tvbridge"
        minSdk = 24
        targetSdk = 36
        // IMPORTANTE: sube versionCode y versionName en cada release (deben
        // coincidir con el tag_name de GitHub, p.ej. v1.0.8 -> "1.0.8") o el
        // comprobador de actualizaciones (UpdateChecker) nunca detectará la
        // versión nueva como más reciente que la instalada.
        versionCode = 38
        versionName = "1.0.38"

        // Se lee de local.properties (no versionado). Consigue la tuya
        // gratis en https://www.themoviedb.org/settings/api
        buildConfigField(
            "String",
            "TMDB_API_KEY",
            "\"${localProperties.getProperty("TMDB_API_KEY", "")}\""
        )

        // Token de Plex (ver comentario en local.properties) para resolver
        // enlaces del catálogo gratuito de watch.plex.tv.
        buildConfigField(
            "String",
            "PLEX_API_TOKEN",
            "\"${localProperties.getProperty("PLEX_API_TOKEN", "")}\""
        )

        // URL del backend de verificación de suscripción (ver carpeta
        // TvRecommendationBridge-backend/), p.ej. https://tu-proyecto.vercel.app
        buildConfigField(
            "String",
            "LICENSE_API_URL",
            "\"${localProperties.getProperty("LICENSE_API_URL", "")}\""
        )

        // Payment Links de Stripe (Dashboard → Payment links) — uno por
        // precio, ya que Stripe no permite mezclar intervalos distintos
        // (mensual/semestral/anual) en un mismo Payment Link. El usuario
        // paga desde el móvil/PC con el mismo email que verificará en el TV.
        buildConfigField(
            "String",
            "MONTHLY_PAYMENT_URL",
            "\"${localProperties.getProperty("MONTHLY_PAYMENT_URL", "")}\""
        )
        buildConfigField(
            "String",
            "LIFETIME_PAYMENT_URL",
            "\"${localProperties.getProperty("LIFETIME_PAYMENT_URL", "")}\""
        )

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        create("release") {
            val storeFilePath = localProperties.getProperty("RELEASE_STORE_FILE")
            if (storeFilePath != null) {
                storeFile = rootProject.file(storeFilePath)
                storePassword = localProperties.getProperty("RELEASE_STORE_PASSWORD")
                keyAlias = localProperties.getProperty("RELEASE_KEY_ALIAS")
                keyPassword = localProperties.getProperty("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
            }
            // Solo firma con la config de release si local.properties trae
            // la keystore configurada; si no, deja el build sin firmar en
            // vez de fallar (para que `assembleDebug` siga funcionando en
            // checkouts nuevos sin keystore).
            if (localProperties.getProperty("RELEASE_STORE_FILE") != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    // Para GuidedStepSupportFragment (pasos de Ajustes con mejor foco de
    // mando) — ver PlayerAppStepFragment/YoutubeAppStepFragment/etc.
    implementation(libs.androidx.leanback)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Solo el "core" de ZXing (generar QR), no la librería completa de
    // escaneo — no hace falta cámara para esto.
    implementation("com.google.zxing:core:3.5.3")
    // Para el chequeo periódico de actualizaciones en segundo plano, y para
    // el worker que refresca la fila de recomendaciones (ver
    // RecommendationChannelWorker).
    implementation(libs.androidx.work.runtime.ktx)
    // API oficial de "canales" de la pantalla de inicio de Android TV (ver
    // RecommendationChannelWorker) — publica nuestra propia fila de
    // recomendaciones, igual que hacen Netflix/HBO/etc.
    implementation(libs.androidx.tvprovider)
    // OCR on-device para el "modo Fire TV" (MediaProjection + lectura de
    // pantalla), ya que allí el AccessibilityService está bloqueado por la
    // plataforma. Variante "bundled" (com.google.mlkit, no
    // com.google.android.gms): el modelo va dentro del APK y NO depende de
    // Google Play Services, que Fire TV no tiene.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}