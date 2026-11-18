# Keep rules for the release build of :lumio (R8 full mode, proguard-android-optimize.txt, see lumio/build.gradle.kts).
# A rule is here only where R8 cannot see how the code is reached. The libraries that ship their own rules are not repeated:
# Room (proguard.txt in its AAR, which keeps the constructors of the RoomDatabase subclasses that Room loads by reflection),
# kotlinx-serialization (META-INF/com.android.tools/r8 in its core jar), and the secp256k1 JNI wrapper (proguard.txt in its AAR).

# web3j and bitcoin-kmp. Lumio uses web3j's crypto and utils in the EVM send (EvmSend.kt), and bitcoin-kmp for Bitcoin keys and
# addresses. Both are kept whole, as in :app: a class R8 drops would fail on the send or derivation path, which the release smoke
# test does not reach.
-keep class org.web3j.crypto.** { *; }
-keep class org.web3j.utils.** { *; }
-keep class fr.acinq.bitcoin.** { *; }

# hummingbird (UR, for the pairing, the send requests and the signatures) and brotli's Java decoder (the version-2 pairing body).
# Neither library ships rules. Both are reached through pairing and send flows, which the release smoke test cannot drive.
-keep class com.sparrowwallet.hummingbird.** { *; }
-keep class org.brotli.dec.** { *; }

# Logging. A release build writes nothing to logcat, so the Log calls are removed, as in :app.
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
    public static *** i(...);
    public static *** w(...);
    public static *** e(...);
    public static *** wtf(...);
}
