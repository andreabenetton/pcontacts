# SPDX-License-Identifier: GPL-3.0-only
# SPDX-FileCopyrightText: 2026 pcontacts contributors
#
# pcontacts ProGuard / R8 rules. Per-module consumer rules ship under
# each module's `consumer-rules.pro`; this file holds the app-wide
# overrides + the dependency rules that don't have first-party
# consumer rules.

# ---------------------------------------------------------------
# BouncyCastle (org.bouncycastle:bcpg-jdk18on, bcprov-jdk18on)
# ---------------------------------------------------------------
# BC resolves Provider services and algorithm names by reflection.
# R8 has no way to know which provider classes are dynamically
# accessed; without these rules SRP / OpenPGP fails at runtime with
# NoSuchAlgorithmException.
-keep class org.bouncycastle.jcajce.provider.** { *; }
-keep class org.bouncycastle.jce.provider.** { *; }
-keep class org.bouncycastle.openpgp.** { *; }
-keep class org.bouncycastle.bcpg.** { *; }
-keep class org.bouncycastle.crypto.** { *; }
-keep class org.bouncycastle.asn1.** { *; }
-keep class org.bouncycastle.jce.** { *; }
-keep class org.bouncycastle.jcajce.** { *; }
-keep class org.bouncycastle.cms.** { *; }
-keep class org.bouncycastle.x509.** { *; }
# BC references javax.naming for JNDI-based config lookup, which is
# unavailable on Android — suppress the warning.
-dontwarn javax.naming.**
-dontwarn javax.security.auth.**
-dontwarn org.bouncycastle.**

# ---------------------------------------------------------------
# Kotlinx serialization
# ---------------------------------------------------------------
# Generated $$serializer classes are referenced reflectively.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class * {
    @kotlinx.serialization.Serializable <fields>;
}
-keepclassmembers class * {
    @kotlinx.serialization.Serializable <fields>;
    public static **$Companion Companion;
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# ---------------------------------------------------------------
# OkHttp / Okio
# ---------------------------------------------------------------
-dontwarn okhttp3.internal.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.jsse.**
-dontwarn org.openjsse.**

# ---------------------------------------------------------------
# Retrofit
# ---------------------------------------------------------------
# Retrofit synthesises proxy classes at runtime and inspects generic
# signatures of suspend-function Continuation<T> parameters to
# determine return types. R8 full mode strips those signatures,
# causing "Unable to create converter for class java.lang.Object".
# Keep the Retrofit plumbing, Kotlin Continuation, and our own API
# interfaces with their full method + type signatures.
-keepattributes Signature, Exceptions, *Annotation*
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-keep,allowobfuscation,allowshrinking class kotlin.coroutines.Continuation
# Keep all Proton API interfaces, DTOs, and serializer companions.
# Retrofit needs full generic signatures for suspend-function return
# types; kotlinx.serialization needs $$serializer classes for every
# DTO. Keeping the whole package is cheaper than debugging which
# specific class R8 strips in full mode.
-keep class io.pcontacts.core.proton.api.** { *; }
-dontwarn retrofit2.**

# ---------------------------------------------------------------
# Coroutines
# ---------------------------------------------------------------
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-dontwarn kotlinx.coroutines.flow.**

# ---------------------------------------------------------------
# ez-vcard
# ---------------------------------------------------------------
# ez-vcard parses/writes vCards via reflection: scribes reflectively
# invoke property, parameter, and util constructors/methods. Keeping
# only `io.scribe` + `property` left `parameter`/`util`/core classes
# tree-shaken, so on release builds a reflectively-invoked method threw
# NoSuchMethodException and every contact failed to parse (debug builds,
# un-minified, were unaffected).
#
# Keep the reflective targets: scribes reflectively touch property,
# parameter, and util members. `io.scribe` + `property` alone (the old
# rules) left `parameter` + `util` tree-shaken — that was the stripped
# member. Deliberately NOT `-keep ezvcard.**` / `ezvcard.*`: keeping the
# root `Ezvcard` facade retains its hCard methods, dragging in jsoup +
# freemarker (→ re2j / java.beans / java.rmi / javax.swing, none on
# Android), which fails R8's missing-class check and bloats the APK. We
# only use text vCards, so the html path stays strippable.
-keep class ezvcard.io.scribe.** { *; }
-keep class ezvcard.property.** { *; }
-keep class ezvcard.parameter.** { *; }
-keep class ezvcard.util.** { *; }
-dontwarn ezvcard.**
# ez-vcard bundles jsoup + freemarker for its (unused) hCard support;
# those reference desktop-JVM classes absent on Android. Silence the
# dangling-reference errors — we never call the hCard path, so R8 still
# strips it and nothing is added to the APK.
-dontwarn org.jsoup.**
-dontwarn com.google.re2j.**
-dontwarn freemarker.**
-dontwarn java.beans.**
-dontwarn java.rmi.**
-dontwarn javax.swing.**
-dontwarn com.sun.org.apache.xml.internal.**

# ---------------------------------------------------------------
# Room
# ---------------------------------------------------------------
# KSP generates Impl classes; keep them + the abstract DAOs.
-keep class **_Impl { *; }
-keep @androidx.room.Database public class * { *; }
-keep @androidx.room.Dao class * { *; }

# ---------------------------------------------------------------
# AndroidX WorkManager
# ---------------------------------------------------------------
-keepclassmembers class * extends androidx.work.Worker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keepclassmembers class * extends androidx.work.CoroutineWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# ---------------------------------------------------------------
# AbstractAccountAuthenticator + AbstractThreadedSyncAdapter
# ---------------------------------------------------------------
# These Android base classes invoke our subclasses via the system
# binder; constructors must survive R8.
-keep public class * extends android.content.AbstractThreadedSyncAdapter {
    public <init>(android.content.Context, boolean);
}
-keep public class * extends android.accounts.AbstractAccountAuthenticator {
    public <init>(android.content.Context);
}

# ---------------------------------------------------------------
# Google Tink (transitively via androidx.security:security-crypto)
# ---------------------------------------------------------------
# Tink references com.google.errorprone.annotations at compile time
# only (the annotations are stripped from the bytecode). Tell R8 to
# stop warning about them.
-dontwarn com.google.errorprone.annotations.**
-dontwarn javax.annotation.**

# ---------------------------------------------------------------
# Stack-trace clarity
# ---------------------------------------------------------------
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
