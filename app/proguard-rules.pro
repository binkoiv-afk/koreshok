# jsoup's optional regex engine and annotations are not shipped.
-dontwarn com.google.re2j.**
-dontwarn org.jspecify.**

# junrar logs through slf4j; no binding is shipped, so it stays silent.
-dontwarn org.slf4j.impl.**

# sherpa-onnx's native code reads its Kotlin config classes by name.
-keep class com.k2fsa.sherpa.onnx.** { *; }

# commons-compress formats Корешок does not use need libraries it does not ship.
-dontwarn org.tukaani.xz.**
-dontwarn com.github.luben.zstd.**
-dontwarn org.brotli.dec.**
-dontwarn org.objectweb.asm.**
-dontwarn org.apache.commons.compress.harmony.**
-dontwarn org.osgi.**
