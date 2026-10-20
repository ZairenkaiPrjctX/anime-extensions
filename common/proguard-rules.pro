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
