# Vosk Android speech demo

Simple Android app with:

- one button to start and stop microphone recording
- text area under the button that shows recognized speech
- offline recognition via Vosk

## Before running

Put a Vosk model into:

`app/src/main/assets/model`

For example, unpack one of the small models from the official Vosk project so that files like `am`, `conf`, `graph`, and `ivector` are inside that `model` folder.

## Main parts

- `app/src/main/java/com/example/voskspeechdemo/MainActivity.kt` handles permission, button state, and recognition callbacks
- `app/src/main/res/layout/activity_main.xml` contains the button and text output
- `app/build.gradle.kts` connects Android + Kotlin + Vosk dependencies
