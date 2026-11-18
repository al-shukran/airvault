# Keep rules for the release build of :app (R8 full mode, proguard-android-optimize.txt, see app/build.gradle.kts).
# A rule is here only where R8 cannot see how the code is reached. The libraries that ship their own rules are not repeated:
# kotlinx-serialization (META-INF/com.android.tools/r8), the secp256k1 JNI wrapper and zxing-cpp (their proguard.txt), and
# Compose, Navigation and CameraX (consumer rules in their AARs).

# Native methods. The JNI library binds each method by its class and method name, so a class that declares a native method
# keeps both. In :app that is BrotliEncoder.encode, bound by Java_app_lensly_app_export_BrotliEncoder_encode in brotli_jni.c.
-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

# web3j and bitcoin-kmp. Lensly uses their public API (org.web3j.crypto, org.web3j.utils, fr.acinq.bitcoin). The signing and key
# code of both is kept whole rather than traced: a class it loses would fail at sign time, where the launch smoke test does not reach.
-keep class org.web3j.crypto.** { *; }
-keep class org.web3j.utils.** { *; }
-keep class fr.acinq.bitcoin.** { *; }
-dontwarn org.web3j.**
-dontwarn fr.acinq.bitcoin.**

# Logging. A release build writes nothing to logcat, so the Log calls are removed. The debug harness logs decoded QR text, and that
# code is not in release.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}
