# JNI entry points and callback methods are addressed by name.
-keep class com.xavierxia.mycelium.llm.NativeBridge { *; }
-keep interface com.xavierxia.mycelium.llm.TokenCallback { *; }
-keepclassmembers class * implements com.xavierxia.mycelium.llm.TokenCallback { *; }
