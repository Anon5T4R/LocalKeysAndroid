# Regras padrão do R8/ProGuard para o projeto.
# A lógica cripto usa o lazysodium (JNA), que não deve ter as classes ofuscadas
# a ponto de quebrar o carregamento nativo — mantenha as classes JNA mapeáveis.
-keep class com.sun.jna.** { *; }
-keep class com.goterl.lazysodium.** { *; }
-keep class net.java.dev.jna.** { *; }
