# jsoup's optional regex engine and annotations are not shipped.
-dontwarn com.google.re2j.**
-dontwarn org.jspecify.**

# junrar logs through slf4j; no binding is shipped, so it stays silent.
-dontwarn org.slf4j.impl.**
