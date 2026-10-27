# Consumer rules, shipped inside the JVM jar (ProGuard and R8 read them from META-INF).
#
# libsealite_jni registers its native methods by class and method name (RegisterNatives in
# JNI_OnLoad) and throws androidx.sqlite.SQLiteException by name (FindClass + ThrowNew). A shrinker
# sees neither from Kotlin, so without these rules a minified application fails to load the library
# or loses the exception type. Same shape as the rules of androidx.sqlite:sqlite-bundled.
-keepclasseswithmembers class io.github.pasichdev.sealite.internal.JniRawDbKt { native <methods>; }
-keepclasseswithmembers class io.github.pasichdev.sealite.internal.JniStatementKt { native <methods>; }
-keep class io.github.pasichdev.sealite.internal.JniStatement
-keep class androidx.sqlite.SQLiteException { <init>(java.lang.String); }
