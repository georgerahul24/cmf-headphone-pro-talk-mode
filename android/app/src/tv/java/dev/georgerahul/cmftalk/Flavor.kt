package dev.georgerahul.cmftalk

/** What differs between the phone app and the TV app. */
object Flavor {
    /** TV: the EQ is always Movie, and there is no Auto EQ. */
    const val TV = true
    /** The screen that opens from notifications. */
    val home: Class<*> = TvActivity::class.java
    /** Release file name before the version, like CMF-Talk-Mode-TV-2.13.0.apk. */
    const val APK_PREFIX = "CMF-Talk-Mode-TV-"
}
