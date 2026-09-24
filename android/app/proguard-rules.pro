# ═══════════════════════════════════════════════════════════════════════════════
# departament — R8 keep rules.
#
# `isMinifyEnabled = true` is only safe because of this file. Everything below is a name the app
# resolves by *string* at runtime — a JSON key, a JNI symbol, an enum constant written into
# storage. R8 has no way to see those references, so anything not kept here is renamed and the
# lookup that depends on it starts returning null. Read the reasoning before deleting a rule.
#
# Deliberately absent: MMKV and WorkManager. Both ship their own consumer rules inside their
# artifacts (mmkv-static's `proguard.txt` keeps the whole JNI surface including the
# `onContentChangedByOuterProcess` reverse-callback that MULTI_PROCESS_MODE exercises;
# work-runtime keeps `* extends androidx.work.ListenableWorker` by name, which is what matters
# because WorkManager persists the worker's fully-qualified name in its own database). Duplicating
# them here would only rot.
# ═══════════════════════════════════════════════════════════════════════════════


# ───────────────────────────────────────────────────────────────────────────────
# 1. Gson models — the field names ARE the wire format.
#
# `dto/V2rayConfig.kt` is ~277 fields across ~40 nested data classes and exactly two of them carry
# @SerializedName. Every other JSON key handed to the native core — inbounds, outbounds,
# streamSettings, tlsSettings, sockopt, fingerprint, publicKey, shortId, serviceName — is the
# Kotlin field name. Gson's own bundled rules only protect *annotated* fields, and explicitly
# allow obfuscation elsewhere on the assumption that @SerializedName supplies the name. Here it
# does not: rename these and `CoreController.startLoop()` receives {"a":{"b":1}}, Xray rejects it,
# and the tunnel never comes up on any server, any protocol, any build.
#
# The same mechanism silently destroys persisted state. MmkvManager stores ProfileItem,
# SubscriptionItem, AssetUrlItem, RulesetItem, ServerAffiliationInfo and WebDavConfig as Gson JSON.
# Obfuscation is stable *within* one build, so a fresh install writes and reads its own short names
# perfectly — the damage only appears at the NEXT release, when R8 picks different names and every
# stored server and subscription becomes unreadable. Users would lose their whole library on
# update, with no error anywhere.
#
# And the backend DTOs: `auth/dto/**` parse the bot backend's responses, a handful annotated, the
# overwhelming majority not. Renamed, every field comes back null, and ApiGson's null-tolerant
# String adapter turns that into "" — sign-in, subscriptions, devices, balance and payment history
# all render blank with no failure to report.
# ───────────────────────────────────────────────────────────────────────────────
-keep class com.v2ray.ang.dto.** { *; }
-keep class com.v2ray.ang.auth.dto.** { *; }
-keepclassmembers class com.v2ray.ang.dto.** { <fields>; }
-keepclassmembers class com.v2ray.ang.auth.dto.** { <fields>; }

# Enums are serialised by Enum.name(). `proguard-android-optimize.txt` keeps values()/valueOf()
# but not the constant fields, and R8 rewrites the name strings in <clinit> to match the new
# names — so across two differently-obfuscated builds a stored "VMESS" reads back as "a" and every
# saved profile's configType is unresolvable.
-keep enum com.v2ray.ang.enums.** { *; }
-keepclassmembers enum com.v2ray.ang.** { *; }

# Gson's generic type resolution reads Signature at runtime.
-keepattributes Signature, RuntimeVisibleAnnotations, AnnotationDefault
-keep class * extends com.google.gson.reflect.TypeToken
-keep,allowobfuscation class com.google.gson.reflect.TypeToken


# ───────────────────────────────────────────────────────────────────────────────
# 2. The gomobile / libv2ray JNI surface — a native abort at startup without this.
#
# libv2ray.aar is produced by `gomobile bind`, and gomobile AARs carry NO consumer rules. The Go
# runtime binds the Java side by literal name through FindClass / GetMethodID / GetFieldID during
# Seq init, before any UI exists.
#
# `proguard-android-optimize.txt` saves classes that *have* native methods, which covers
# Libv2ray and CoreController — but not the two pure-Java callback interfaces Go looks up by
# signature: CoreCallbackHandler.onEmitStatus(JLjava/lang/String;)J, implemented by
# CoreServiceManager's private CoreCallback, and ProcessFinder.findProcessByConnection, implemented
# by XrayProcessFinder. Nothing keeps those. Rename them and the core cannot report status, cannot
# request shutdown, and per-app routing loses its uid lookup.
# ───────────────────────────────────────────────────────────────────────────────
-keep class go.** { *; }
-keep class libv2ray.** { *; }
-keep interface libv2ray.** { *; }
-dontwarn go.**
-dontwarn libv2ray.**


# ───────────────────────────────────────────────────────────────────────────────
# 3. hev-socks5-tunnel JNI entry points.
#
# `compile-hevtun.sh` builds the library with -DPKGNAME=com/v2ray/ang/service, so the C symbols
# are Java_com_v2ray_ang_service_TProxyService_TProxyStartService and friends — bound to the class
# package, not to applicationId. These survive today only as a side effect of which default
# ProGuard file build.gradle.kts happens to name. Make the dependency explicit rather than lucky.
# ───────────────────────────────────────────────────────────────────────────────
-keepclasseswithmembernames,includedescriptorclasses class com.v2ray.ang.service.TProxyService {
    native <methods>;
}


# ───────────────────────────────────────────────────────────────────────────────
# 4. Toolchain and optional dependencies.
# ───────────────────────────────────────────────────────────────────────────────
# Kotlin coroutines / reflection metadata.
-keepattributes InnerClasses, EnclosingMethod, *Annotation*
-dontwarn kotlinx.coroutines.**

# OkHttp's optional TLS platform providers are compile-time references to artifacts that are not
# on this app's classpath; without these the R8 step fails on missing classes.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# Readable crash reports in «Журнал» — a stack trace with no line numbers is not a bug report.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
