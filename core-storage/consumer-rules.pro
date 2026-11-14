# Keeps the DocumentsProvider intact under R8 (also kept automatically via
# manifest references; this is belt and braces).
-keep class dev.mono.storage.provider.MonoProvider { *; }
