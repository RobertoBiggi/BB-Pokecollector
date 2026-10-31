# kotlinx.serialization and OkHttp already include their own R8 rules.
# Keeps the serializable DTOs of the core module used for TCGdex and for backups.
-keep,includedescriptorclasses class com.robertobiggi.cardcollection.core.**$$serializer { *; }
-keepclassmembers class com.robertobiggi.cardcollection.core.** {
    *** Companion;
}
-keepclasseswithmembers class com.robertobiggi.cardcollection.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}
