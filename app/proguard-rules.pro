# Manifest-referenced components are kept automatically; this file only adds
# belt-and-braces rules for reflection-free safety.
-keep class dev.mono.storage.provider.MonoProvider { *; }
-dontwarn org.slf4j.**
