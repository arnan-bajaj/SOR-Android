# Firestore data is read and written as maps, so no model classes need keeping.
# Credential Manager and Google ID (their consumer rules cover the rest).
-if class androidx.credentials.CredentialManager
-keep class androidx.credentials.playservices.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }

# WorkManager (used by the Glance widget) builds its Room database by reflection
-keep class * extends androidx.room.RoomDatabase { <init>(); }
-keep class androidx.work.impl.WorkDatabase_Impl { *; }
