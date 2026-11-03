# JSch loads its algorithm implementations by class name from its config table.
-keep class com.jcraft.jsch.** { *; }
-dontwarn com.jcraft.jsch.**
-dontwarn org.bouncycastle.**
-dontwarn org.newsclub.**
-dontwarn com.sun.jna.**
-dontwarn org.apache.logging.**
-dontwarn org.slf4j.**
-dontwarn org.ietf.jgss.**
