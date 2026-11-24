# Build the APK on GitHub (no Android Studio required)

## 1. Create a public repository

1. Sign in to GitHub and open https://github.com/new
2. Repository name: `ScreenReaderAndroid`
3. Select **Public**.
4. Do not initialize with a README, .gitignore, or license (the project already contains files).
5. Create the repository.

## 2. Upload the project from your browser

1. Open the new repository.
2. Choose **Add file → Upload files**.
3. Extract `ScreenReaderAndroid_Source.zip` on your PC.
4. Open the extracted `ScreenReaderAndroid` folder and select all its contents, including the hidden `.github` folder and `.gitignore`.
5. Drag the selected contents into GitHub's upload area. Make sure `build.gradle.kts`, `settings.gradle.kts`, `app/`, and `.github/` appear at the repository root—not inside an extra nested `ScreenReaderAndroid/` folder.
6. Commit directly to the `main` branch.

## 3. Download the APK

1. Open the repository's **Actions** tab.
2. Select the latest **Build Android APK** workflow run.
3. Wait until the job completes successfully (green check mark).
4. Open the run and scroll to **Artifacts**.
5. Download `ScreenReaderAndroid-debug-apk` and extract it. The APK is `app-debug.apk`.

Every push to `main` starts another cloud build. You can also start one manually from **Actions → Build Android APK → Run workflow**.

## Notes

- GitHub Actions builds the debug APK in the cloud; Android Studio and the Android SDK are not needed on your Windows PC.
- The first build may take several minutes while dependencies are downloaded.
- The APK artifact is retained for 30 days by this workflow.
- This is a debug APK, not a Play Store release-signed APK.
- The source project has not yet been compiled or device-tested. If the workflow fails, open the failed job, copy the first meaningful error from the build log, and use it to fix the project before relying on the APK.
