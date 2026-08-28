# TestCompass

<p align="center">
  <img src="sample-app/workflow_1200x760.gif" alt="Workflow demo" width="350">
</p>

[TestCompass](https://plugins.jetbrains.com/plugin/30034-vandytest) is an IntelliJ IDEA plugin that helps students understand where their tests are weak. It reads IntelliJ's active coverage data, ranks under-tested production methods, and can generate conceptual test recommendations.

<!-- Plugin description -->
Provides an IntelliJ Platform plugin that identifies coverage hotspots from the IDE's coverage data, shows them in a dedicated tool window, and generates AI-powered test recommendations for the most under-covered methods. Intended for educational use, it highlights weakly tested code and offers guidance without generating test code directly.
<!-- Plugin description end -->

## Install From JetBrains Marketplace

1. Open IntelliJ IDEA.
2. Go to `Settings > Plugins > Marketplace`.
3. Search for `TestCompass`.
4. Install the plugin from the JetBrains Marketplace listing:
   `https://plugins.jetbrains.com/plugin/30034-vandytest`
5. Restart IntelliJ IDEA if prompted.

## Install From Disk (If you cannot find the plugin from the Marketplace)

If TestCompass does not appear in the JetBrains Marketplace:

1. Download the latest TestCompass plugin ZIP file (for example, `TestCompass-0.0.5.zip`). Do not extract it.
2. Open IntelliJ IDEA.
3. Go to `Settings > Plugins`.
4. Click the gear icon and select `Install Plugin from Disk...`.
5. Select the downloaded TestCompass ZIP file and click `OK`.
6. Restart IntelliJ IDEA if prompted.
7. Open the project you want to test, such as the MarsRover project.

## First-Time Setup

When IntelliJ opens a project after installation, TestCompass shows a setup dialog. Enter:

- `Amplify token`: the token provided by the instructor.

You can update these later in `Settings > Tools > TestCompass` or by searching `TestCompass` in IntelliJ settings.

## Workflow

1. Open the assignment project in IntelliJ IDEA.
2. Open or create the JUnit test file for the assignment.
3. Run tests with coverage:
   - Right-click the test class, test folder, or project.
   - Choose `Run 'All Tests' with Coverage` or `Run '<TestClass>' with Coverage`.
4. Run TestCompass:
   - Go to `Tools > TestCompass`.
5. Open the `TestCompass` tool window on the right side of IntelliJ.
6. Review the ranked methods with the most missed coverage.
7. Click `Generate recommendations` to receive guidance for improving tests.
8. Use the recommendations to decide what behaviors, edge cases, and assertions to add.
9. Re-run tests with coverage and run TestCompass again to check for improvement.

TestCompass requires an active IntelliJ coverage suite. If it says `No active coverage suite`, run tests with coverage first and then run `Tools > TestCompass` again.


## Build For Marketplace

This project uses Gradle and the IntelliJ Platform Gradle Plugin. Java 25/26 requires Gradle 9.4 or newer; the wrapper is configured for Gradle 9.6.1.

```bash
./gradlew buildPlugin
```

The uploadable Marketplace ZIP is generated in:

```text
build/distributions/
```
