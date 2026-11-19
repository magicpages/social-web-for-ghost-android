# kotlinx.serialization, Ktor, Hilt, Coil and Tink ship their own consumer R8 rules.

# ML Kit creates its component registrars by reflection (MlKitInitProvider). R8 full mode doesn't
# keep their no-argument constructors on its own, and without them translation fails on start.
-keep class com.google.mlkit.** implements com.google.firebase.components.ComponentRegistrar { <init>(); }
