plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // KSP 版本号是 `<kotlin>-<ksp>` 两段拼起来的，必须与上面的 Kotlin 版本严格配对，
    // 不能只升其中一段。1.9.24 对应 1.0.20。
    id("com.google.devtools.ksp") version "1.9.24-1.0.20" apply false
}
