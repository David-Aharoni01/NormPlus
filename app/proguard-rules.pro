# ── App ──────────────────────────────────────────────────────────────────────
-keep class com.norm2hacked.** { *; }
-keepattributes *Annotation*

# ── Room ──────────────────────────────────────────────────────────────────────
# Entities, DAOs, and the database class are accessed reflectively by Room.
-keep @androidx.room.Entity class ** { *; }
-keep @androidx.room.Dao class ** { *; }
-keep @androidx.room.Database class ** { *; }
-keepclassmembers @androidx.room.Entity class ** { *; }
-keepclassmembers @androidx.room.Dao class ** { *; }
-keepclassmembers @androidx.room.Database class ** { *; }
# Room's generated _Impl classes must survive shrinking.
-keep class **_Impl { *; }

# ── Hilt ─────────────────────────────────────────────────────────────────────
# Hilt generates DaggerXxx and Hilt_Xxx component classes at compile time.
# If they're obfuscated, Hilt cannot find or instantiate them at runtime.
-keep class dagger.hilt.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ActivityComponentManager { *; }
-keepclasseswithmembernames class * { @dagger.hilt.* *; }
-keepclasseswithmembernames class * { @javax.inject.* *; }
-keep class **_HiltComponents { *; }
-keep class **_HiltModules { *; }
-keep class **_MembersInjector { *; }
-keep class **_Factory { *; }

# ── Kotlinx Serialization ─────────────────────────────────────────────────────
# Serializable classes and their companion serializers must not be renamed.
-keepattributes RuntimeVisibleAnnotations
-keep @kotlinx.serialization.Serializable class ** { *; }
-keepclassmembers @kotlinx.serialization.Serializable class ** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keep class kotlinx.serialization.** { *; }
-dontwarn kotlinx.serialization.**

# ── Kotlin coroutines ─────────────────────────────────────────────────────────
# Coroutines use reflection for debugging and internal state; volatile fields must survive.
-keepclassmembernames class kotlinx.coroutines.** {
    volatile <fields>;
}
-dontwarn kotlinx.coroutines.**

# ── Kotlin stdlib ─────────────────────────────────────────────────────────────
-dontwarn kotlin.**
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-keepclassmembers class kotlin.Lazy { *; }

# ── AndroidX / Compose ────────────────────────────────────────────────────────
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**
-keep class androidx.lifecycle.** { *; }

# ── BLE / Android Bluetooth ───────────────────────────────────────────────────
# BluetoothGattCallback subclasses are instantiated by the system.
-keep class * extends android.bluetooth.BluetoothGattCallback { *; }

# ── Notification listener ─────────────────────────────────────────────────────
# The system instantiates NotificationListenerService directly by class name.
-keep class * extends android.service.notification.NotificationListenerService { *; }
