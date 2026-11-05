plugins { id("com.android.application") }
android {
    namespace="app.sososafe.fixture"
    compileSdk { version=release(37){minorApiLevel=0} }
    defaultConfig {applicationId="app.sososafe.fixture";minSdk=26;targetSdk=35;versionCode=1;versionName="1"}
    compileOptions {sourceCompatibility=JavaVersion.VERSION_17;targetCompatibility=JavaVersion.VERSION_17}
}
