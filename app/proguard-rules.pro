# 本文件原本是空的（骨架阶段没开混淆，见 app/build.gradle.kts 的 isMinifyEnabled=false）。
# 下面这些是为了「拍照发邮件」引入的 JavaMail 规则——现在开着也是无害的，
# 但等哪天真的打开 R8 再补，就来不及了：JavaMail 大量靠反射查 DataHandler，
# 混淆之后症状是**发信静默失效**（或运行时才抛 VerifyError），编译期不会有任何提示。

-keep class javax.mail.** { *; }
-keep class com.sun.mail.** { *; }
-keep class com.sun.activation.** { *; }

# 这些 handler 只在 META-INF/mailcap 里被按名字引用，R8 看不出有人用它们。
-keep class com.sun.mail.handlers.** { *; }

# 自签证书用的 SSL 工厂：JavaMail 拿 `mail.smtp.ssl.socketFactory.class` 里的**类名**
# 去 Class.forName + newInstance。类名一旦被混淆，只有开了混淆的 release 包会发现，
# 症状是"打开了信任自签证书反而连不上"。
-keep class me.xiaozhi.androidclient.email.TrustingSslSocketFactory { public <init>(); }

-dontwarn javax.mail.**
-dontwarn com.sun.mail.**
-dontwarn com.sun.activation.**
