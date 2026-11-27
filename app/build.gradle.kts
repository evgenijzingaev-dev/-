plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "ru.smartbasket.app"
    compileSdk = 34
    defaultConfig { applicationId = "ru.smartbasket.app"; minSdk = 26; targetSdk = 34; versionCode = 5; versionName = "0.5.0" }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}

dependencies { testImplementation("junit:junit:4.13.2") }
