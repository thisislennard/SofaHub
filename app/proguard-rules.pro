# kotlinx.serialization: generierte Serializer behalten (die Bibliothek bringt eigene Regeln mit, das hier ist die Absicherung
# für @Serializable-Klassen der App).
-keepclassmembers @kotlinx.serialization.Serializable class de.sofahub.app.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepattributes *Annotation*, InnerClasses, Signature

# OkHttp bringt eigene Consumer-Regeln mit; optionale Plattform-Klassen fehlen auf Android absichtlich.
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
