# kotlinx.serialization e OkHttp includono già le proprie regole R8.
# Mantiene i DTO serializzabili del modulo core usati per TCGdex e per i backup.
-keep,includedescriptorclasses class com.robertobiggi.cardcollection.core.**$$serializer { *; }
-keepclassmembers class com.robertobiggi.cardcollection.core.** {
    *** Companion;
}
-keepclasseswithmembers class com.robertobiggi.cardcollection.core.** {
    kotlinx.serialization.KSerializer serializer(...);
}
