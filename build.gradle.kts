plugins {
    id("com.android.application") version "9.4.0" apply false
    // AGPに組み込まれたKotlin（2.2.10）より新しい版を使う。音声翻訳のML Kit GenAIが、Kotlin 2.4の形式で作られているため。
    id("org.jetbrains.kotlin.android") version "2.4.0" apply false
}
