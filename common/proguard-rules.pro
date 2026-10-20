# Extensions are loaded by the host app through reflection and share its runtime libraries
# (extensions-lib, OkHttp, Jsoup, kotlinx.serialization), which are compileOnly here.
-dontoptimize
-dontpreverify

-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# kotlinx.serialization: keep generated serializers of @Serializable classes.
# https://github.com/Kotlin/kotlinx.serialization/tree/dev/rules
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault,Signature,InnerClasses,EnclosingMethod

-keepclassmembers @kotlinx.serialization.Serializable class ** {
    static ** Companion;
}

-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}

-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

-keepclassmembers public class **$$serializer {
    private ** descriptor;
}

-if @kotlinx.serialization.Serializable class **
-keep,allowshrinking,allowoptimization,allowobfuscation,allowaccessmodification class <1>

# Injekt captures generic types through subclasses of FullTypeReference and reads them via
# reflection (`Injekt.get<Application>()`); without these, R8 strips the type and the lookup fails
# with "TypeReference constructed without actual type information".
-keep class * extends uy.kohesive.injekt.api.FullTypeReference
