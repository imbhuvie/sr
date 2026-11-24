# Screen Reader (Android / Kotlin)

A native Android starter application that captures the visible screen with Android MediaProjection, crops a user-configured region of interest (ROI), detects the rightmost colored result icon, and stores one Room/SQLite row per scheduled capture cycle.

## Included

- Native Kotlin Android application (landscape UI)
- MediaProjection foreground service
- Configurable capture interval (5–300 seconds)
- Configurable retry count (0–5 additional attempts)
- Configurable retry delay (250–10,000 ms)
- Manual ROI calibration by dragging a rectangle over the latest captured screenshot
- Independent image-processing component (`processing/IconDetector.kt`)
- Rightmost-candidate selection
- Color-family classification: blue → D/Dragon, yellow/orange → T/Tiger, green → Tie
- Confidence threshold
- Room database backed by SQLite
- One database row per scheduled cycle, including failed cycles
- Latest-record viewer, detection preview, and CSV export

## Open in Android Studio

1. Install Android Studio and a JDK 17.
2. Open the `ScreenReaderAndroid` folder as an existing Gradle project.
3. Allow Android Studio to sync Gradle and install Android SDK Platform 35 / Build Tools.
4. Connect an Android device (Android 8.0 / API 26 or later) in landscape mode and run the `app` configuration.
5. Start monitoring and approve Android's screen-capture prompt.
6. Switch to the target application. After a frame is captured, open the foreground notification and tap **Freeze frame**. Return to Screen Reader and choose **Calibrate Region**; the last frozen screenshot is shown. Drag around the complete history strip, then save. Tap **Resume** in the notification to continue monitoring.
7. Set the capture interval and retry behavior in **Settings**. Restart monitoring after changing settings for the interval schedule to restart cleanly.

If Android Studio asks for a Gradle distribution, use Gradle 8.9 or let Android Studio resolve the compatible distribution for Android Gradle Plugin 8.7.3.

## Detection tuning

The detector uses color segmentation rather than OCR or a trained model. It looks for saturated blue, green, and yellow/orange pixels inside the ROI, groups active columns into icon candidates, chooses the rightmost candidate, and classifies it by the dominant color family.

Initial ROI values are approximate defaults for the supplied 1600×720 sample image. Calibrate on the actual device. The ROI should include the history icons only and exclude unrelated colored elements. The color ranges and confidence threshold may need tuning if the game changes its theme, uses different colors, or applies strong animation/lighting effects.

The image-processing API is independent: `IconDetector.detect(bitmap, settings)` returns a `DetectionResult` and does not access the capture service or database.

## Important Android limitations

- MediaProjection requires explicit user consent for each new capture session. Android displays its screen-sharing indicator/notification. The persistent notification includes **Freeze frame** and **Resume** actions to support calibration without overwriting the target-app screenshot.
- The target app must permit screen capture. Apps using Android's secure-window protection may return a blank/blocked frame.
- Keep the target app visible and the device in landscape mode during monitoring. Android may pause or terminate capture if the projection is stopped, the device locks, or the OS reclaims resources.
- This starter uses the app's current display metrics at the time capture starts. If resolution/orientation changes, stop monitoring, reopen in landscape, and calibrate again.
- The color detector is a practical first implementation, not a guarantee of perfect recognition. Validate it with real device screenshots before relying on its records.
- The app does not interact with the game, place bets, or automate taps. It only captures and classifies visible pixels.

## Database

Database file: `screen_reader.db` (private app storage).

Table: `capture_records`

Each scheduled cycle inserts exactly one final row. Failed initial attempts are retried according to settings; intermediate retry failures are not separately inserted. If all attempts fail, the cycle is stored with a failure status such as `NO_ICON`, `UNKNOWN`, or `CAPTURE_TIMEOUT`.

## Current implementation notes

This source project is provided for Android Studio. It has not been built or device-tested in this environment because an Android SDK/Gradle installation is not available here. Run Gradle sync/build in Android Studio and test calibration and color thresholds on the target phone before using it for ongoing data collection.
