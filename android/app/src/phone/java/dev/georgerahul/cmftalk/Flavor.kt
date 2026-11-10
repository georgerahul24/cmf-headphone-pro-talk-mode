package dev.georgerahul.cmftalk

/** What differs between the phone app and the TV app. */
object Flavor {
    const val TV = false
    /** The screen that opens from notifications. */
    val home: Class<*> = MainActivity::class.java
    /** Release file name before the version, like CMF-Talk-Mode-2.13.0.apk. */
    const val APK_PREFIX = "CMF-Talk-Mode-"
}
