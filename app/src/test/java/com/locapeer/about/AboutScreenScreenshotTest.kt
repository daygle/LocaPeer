package com.locapeer.about

import android.app.Application
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.RoborazziOptions
import com.github.takahirom.roborazzi.captureRoboImage
import com.locapeer.ui.theme.LocaPeerTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Golden-image test for the About screen's open-source list at 360dp wide - the narrowest width
 * the app supports - in every locale whose library descriptions are long enough to wrap.
 *
 * Each row lays the description out in its own weighted column, so a long translation wraps onto a
 * second line beside the library name instead of running underneath it. The golden PNGs in
 * app/src/test/screenshots pin that layout: a description that stops wrapping, shrinks, or starts
 * overlapping its name changes pixels and fails testDebugUnitTest.
 *
 * Re-record after an intended layout change with
 * `./gradlew :app:testDebugUnitTest -Proborazzi.test.record=true`, then review the changed PNGs.
 *
 * The goldens were recorded on Linux x86_64 (JDK 25, Robolectric 4.18.1), which is what CI runs, so
 * an exact match is expected there. Robolectric rasterises through its own bundled Skia build, and
 * those builds are not pixel-identical across operating systems: a failure on macOS points at the
 * renderer rather than at the layout. Compare the diff Roborazzi writes under
 * app/build/outputs/roborazzi/ before re-recording, and re-record only for an intended change.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(
    // 360dp is the narrowest supported width, on a typical 360x800 phone window tall enough for the
    // whole scrolling screen to fit in one image.
    qualifiers = "w360dp-h800dp-xhdpi",
    // The real Application opens SQLCipher in onCreate, whose native library doesn't load on the JVM.
    application = Application::class,
    sdk = [35],
)
class AboutScreenScreenshotTest {

    @get:Rule
    val compose = createComposeRule()

    @Test fun english() = capture("en")

    @Test fun german() = capture("de")

    @Test fun french() = capture("fr")

    @Test fun telugu() = capture("te")

    @Test fun marathi() = capture("mr")

    @Test fun bengali() = capture("bn")

    @Test fun malayalam() = capture("ml")

    @Test fun polish() = capture("pl")

    @Test fun burmese() = capture("my")

    private fun capture(locale: String) {
        // Applied before the rule launches its activity, so the composition reads the translated
        // strings from the matching values-* resources.
        RuntimeEnvironment.setQualifiers("+$locale")
        compose.setContent {
            // dynamicColor off: the platform palette would make the goldens depend on Robolectric's
            // emulated system theme rather than on the app's own colors.
            LocaPeerTheme(dynamicColor = false) {
                AboutScreen(
                    onNavigateBack = {},
                    versionName = "1.1.45",
                    versionCode = 10145,
                )
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(
            filePath = "src/test/screenshots/about-360dp-$locale.png",
            roborazziOptions = RoborazziOptions(
                compareOptions = RoborazziOptions.CompareOptions(
                    // Tolerate antialiasing noise, but far less than a single wrapped or
                    // overlapping line moves.
                    changeThreshold = 0.001F,
                ),
            ),
        )
    }
}
