# Vanderbilt Test Plugin – Coverage Hotspots & AI Test Recommendations

This repository contains **vanderbiltTestPlugin**, an IntelliJ Platform plugin written in Kotlin. The plugin’s primary goal (on the `coverageAPI`/current branch) is to help students and developers:

- Identify **coverage hotspots** (methods with poor test coverage) using IntelliJ’s built‑in coverage data.
- Visualize those hotspots in a dedicated tool window.
- Generate **natural-language test recommendations** for the worst-covered methods using an external Chat API (Amplify).

The project also includes some of the standard IntelliJ Platform Plugin template functionality (a sample tool window and project service).

---

## Table of Contents

- [High-Level Overview](#high-level-overview)
- [Key Features](#key-features)
  - [Coverage Hotspot Detection](#coverage-hotspot-detection)
  - [Coverage Hotspots Tool Window](#coverage-hotspots-tool-window)
  - [AI-Powered Test Recommendations](#ai-powered-test-recommendations)
  - [Template Sample Code](#template-sample-code)
- [Architecture and Main Components](#architecture-and-main-components)
  - [Coverage Analysis Flow](#coverage-analysis-flow)
  - [AI Integration Flow](#ai-integration-flow)
- [How to Build and Run the Plugin](#how-to-build-and-run-the-plugin)
  - [Prerequisites](#prerequisites)
  - [Opening in IntelliJ IDEA](#opening-in-intellij-idea)
  - [Running in a Sandbox IDE](#running-in-a-sandbox-ide)
- [Using the Coverage Features](#using-the-coverage-features)
  - [1. Run Tests with Coverage](#1-run-tests-with-coverage)
  - [2. Analyze Coverage (IDE API)](#2-analyze-coverage-ide-api)
  - [3. Explore the Coverage Hotspots Tool Window](#3-explore-the-coverage-hotspots-tool-window)
  - [4. Generate AI Test Recommendations](#4-generate-ai-test-recommendations)
- [Configuration: Amplify Chat API](#configuration-amplify-chat-api)
- [Code Reference](#code-reference)
  - [Coverage-Related Classes](#coverage-related-classes)
  - [Core Template Classes](#core-template-classes)
  - [Tests](#tests)
- [What This Project Demonstrates](#what-this-project-demonstrates)
- [Notes and Limitations](#notes-and-limitations)
- [License](#license)

---

## High-Level Overview

This plugin extends the IntelliJ Platform with tooling to:

1. **Read coverage data from IntelliJ’s current coverage suite** (i.e., the results you get when you run tests with coverage inside the IDE).
2. **Aggregate coverage at the method level** and rank methods by how many lines are *missed* (uncovered).
3. **Filter out test classes** and library code, focusing on project production code only.
4. **Display the worst-covered methods in a “Coverage Hotspots” tool window**.
5. **Integrate with an external Chat API** to generate human-readable recommendations for **new or improved tests** for a small set of the worst methods.

Intended educational use:

- Help students see which parts of their code are poorly tested.
- Provide structured guidance on how to design additional tests, **without** directly generating the test code for them.

---

## Key Features

### Coverage Hotspot Detection

Implemented in [`AnalyzeCoverageAction`](src/main/kotlin/com/github/ronah123/vanderbilttestplugin/actions/AnalyzeCoverageAction.kt):

- Adds a **Tools → Analyze Coverage (IDE API)** menu action (registered in `plugin.xml`).
- When invoked:
  1. Retrieves the **current coverage suite** via `CoverageDataManager`.
  2. Reads its underlying coverage data (an IntelliJ coverage `ProjectData` object).
  3. Aggregates coverage **per method**:
    - Counts total lines and how many are covered.
    - Computes `missedLines` and a `linePct` (line coverage percentage).
  4. Filters the list down to:
    - Classes belonging to the current project.
    - Non‑test source files only.
  5. Sorts methods by:
    - Highest number of missed lines first,
    - Then by lowest coverage percentage.
  6. Sends the top N results (default: 25) to the **Coverage Hotspots tool window** via a project service.

### Coverage Hotspots Tool Window

Implemented via:

- [`CoverageHotspotsToolWindowFactory`](src/main/kotlin/com/github/ronah123/vanderbilttestplugin/coverage/CoverageHotspotsToolWindowFactory.kt)
- [`CoverageHotspotsPanel`](src/main/kotlin/com/github/ronah123/vanderbilttestplugin/coverage/CoverageHotspotsPanel.kt)
- [`CoverageHotspotsService`](src/main/kotlin/com/github/ronah123/vanderbilttestplugin/coverage/CoverageHotspotsService.kt)

Registered in `plugin.xml` as a tool window:

```xml
<toolWindow
    id="Coverage Hotspots"
    anchor="right"
    factoryClass="com.github.ronah123.vanderbilttestplugin.coverage.CoverageHotspotsToolWindowFactory"
    canCloseContents="true"/>
```

Behavior:

- Shows a table with columns:
  - `#` (rank),
  - `Missed/Total` (missed lines vs total),
  - `%Cov` (percentage coverage),
  - `Method` (`<class FQN>#<method>`).
- Double-clicking a row opens the corresponding class in the editor.
- Contains a **“Generate recommendations”** button to trigger AI-based suggestions.

### AI-Powered Test Recommendations

Implemented in:

- [`CoverageHotspotsPanel`](onGenerateRecommendationsClicked)
- [`CodeExtraction`](src/main/kotlin/com/github/ronah123/vanderbilttestplugin/coverage/CodeExtraction.kt)
- [`AmplifyChatClient`](src/main/kotlin/com/github/ronah123/vanderbilttestplugin/coverage/AmplifyChatClient.kt)
- [`RecommendationsDialog`](src/main/kotlin/com/github/ronah123/vanderbilttestplugin/coverage/RecommendationsDialog.kt)
- [`CoverageAIConfig`](src/main/kotlin/com/github/ronah123/vanderbilttestplugin/coverage/CoverageAIConfig.kt)

Flow:

1. When the user clicks **“Generate recommendations”** in the Coverage Hotspots panel:
  - Takes the top `MAX_METHODS_TO_REVIEW` methods from the table.
  - Resolves their **source code** using PSI (`JavaPsiFacade`) and method signatures.
  - Attempts to find a **relevant test file** in the project (e.g., `ClassNameTest.kt`).
2. Builds a **large language model prompt** that includes:
  - The current test file (if found).
  - Each production method’s source code.
  - Detailed instructions about designing good tests and not outputting test code directly.
3. Sends the prompt to an external Chat API (Amplify) using `HttpClient`.
4. Displays the result in a **dialog with two tabs**:
  - **Recommendations** – AI-generated advice on how to improve tests.
  - **Prompt (preview)** – The exact prompt that was sent to the model (useful for debugging/verification).

The dialog also includes a **“Copy recommendations”** button to copy text to the clipboard.

---

## Architecture and Main Components

### Coverage Analysis Flow

1. **User runs tests with coverage** using IntelliJ’s coverage runner.
2. **User invokes** `Tools → Analyze Coverage (IDE API)`:
  - [`AnalyzeCoverageAction.actionPerformed`](src/main/kotlin/com/github/ronah123/vanderbilttestplugin/actions/AnalyzeCoverageAction.kt) creates a background task.
3. **Action reads the active coverage suite**:
  - Uses `CoverageDataManager.getInstance(project).currentSuitesBundle`.
  - Retrieves `bundle.coverageData` (underlying `ProjectData`).
4. **Method-level aggregation**:
  - `aggregateByMethod`:
    - Tries a **typed** reflection path if `ProjectData` classes are available (`com.intellij.rt.coverage.data.ProjectData`, `ClassData`, `LineData`).
    - Falls back to a more generic reflective approach if necessary.
  - Aggregates coverage data into a list of `MethodHit`:
    ```kotlin
    data class MethodHit(
        val classFqn: String,
        val method: String,
        val totalLines: Int,
        val coveredLines: Int,
        val missedLines: Int,
        val linePct: Double
    )
    ```
5. **Filter to project production code**:
  - `filterMethodsToProject`:
    - Uses `JavaPsiFacade` and `ProjectFileIndex` to:
      - Ensure classes resolve to project content.
      - Exclude test source content.
    - Applies a heuristic (`looksLikeTestFqn`) to discard obvious test classes by name (`*Test`, `*IT`, `*Spec`, etc.).
6. **Ranking and display**:
  - Sorts by `missedLines` descending, then `linePct` ascending.
  - Passes the resulting list into `CoverageHotspotsService.showInToolWindow`, which shows the **Coverage Hotspots** tool window and loads the data into `CoverageHotspotsPanel`.

### AI Integration Flow

1. **User clicks “Generate recommendations”** in `CoverageHotspotsPanel`.
2. The panel:
  - Collects the top `MAX_METHODS_TO_REVIEW` methods (from the table rows).
  - For each `(classFqn, methodKey)` pair, resolves a `MethodBundle`:
    - Uses PSI to find the corresponding `PsiClass` and `PsiMethod`.
    - Extracts trimmed method text (bounded by `MAX_METHOD_CHARS`).
  - Attempts to resolve **one representative test file** via `CodeExtraction.resolveSingleTestFile`.
3. **Prompt building**:
  - `CodeExtraction.buildPrompt` constructs a multi-section prompt:
    - High-level instructions for the AI about:
      - Identifying test gaps,
      - Designing executable tests (with assertions),
      - Covering requirements and bugs.
    - Includes:
      - The full or truncated test file.
      - Each production method’s code in fenced code blocks.
  - Applies a global size budget (`MAX_PROMPT_CHARS`) to keep prompts within the configured limit.
4. **Chat call**:
  - `AmplifyChatClient.chatOnce(prompt)` sends a POST request to `${AMPLIFY_BASE}/chat`:
    - Authorization header uses `AMPLIFY_BEARER` token.
    - `modelId` specified by `MODEL_ID`.
  - Parses the response and returns text representative of the recommendations.
5. **Result UI**:
  - `RecommendationsDialog` shows both:
    - The recommendations.
    - The exact prompt preview.
  - Includes a **Copy** button to easily export the advice.

---

## How to Build and Run the Plugin

### Prerequisites

- **IntelliJ IDEA** (Community or Ultimate) with **IntelliJ Platform Plugin** development support.
- **JDK** compatible with your IntelliJ plugin SDK (e.g., JDK 17).
- Project likely uses **Gradle** (typical for Kotlin IntelliJ plugins), but use your actual build files to confirm.

You also need an **Amplify API token** if you want to exercise the AI integration (see [Configuration](#configuration-amplify-chat-api)).

### Opening in IntelliJ IDEA

1. Clone the repository:
   ```bash
   git clone https://github.com/ronah123/vanderbiltTestPlugin.git
   cd vanderbiltTestPlugin
   ```
2. In IntelliJ IDEA:
  - `File → Open…` → select the project directory.
  - Let IntelliJ import the build configuration and download dependencies.

### Running in a Sandbox IDE

Assuming a Gradle-based IntelliJ plugin (the common template):

- From the command line:
  ```bash
  ./gradlew runIde
  ```
- Or from IntelliJ:
  - Use the **“runIde”** Gradle task or existing run configuration.

This launches a sandbox IntelliJ instance with the plugin installed.

---

## Using the Coverage Features

### 1. Run Tests with Coverage

To provide data for the plugin:

1. In the sandbox IDE, open a project with Java/Kotlin code and tests.
2. Run tests with coverage using IntelliJ’s **IDE runner** (not Gradle’s runner):
  - Use a Run Configuration for your tests.
  - Enable coverage (Run with Coverage / Coverage options).
  - Use the IntelliJ coverage runner (as suggested by the IDE).

If no coverage suite is active, the plugin will show an informative message.

### 2. Analyze Coverage (IDE API)

Once coverage has been collected:

1. In the sandbox IDE, go to the top menu:
  - `Tools → Analyze Coverage (IDE API)`
2. The plugin:
  - Reads the **current coverage suite**.
  - Aggregates and ranks methods by missed lines.
  - Populates the **Coverage Hotspots** tool window.

If no coverage is found or coverage data is of an unsupported type, the plugin shows an information dialog with guidance.

### 3. Explore the Coverage Hotspots Tool Window

Open the **Coverage Hotspots** tool window (anchored on the right side):

- You’ll see a table of methods sorted by how many lines are uncovered.
- Each row includes:
  - Rank,
  - Missed/total lines,
  - Percentage coverage,
  - Fully qualified `ClassName#methodSignature`.
- **Double-click** a row to open the corresponding class in the editor.

This view helps you quickly identify functions that most urgently need better tests.

### 4. Generate AI Test Recommendations

In the **Coverage Hotspots** tool window:

1. Click the **“Generate recommendations”** button.
2. The plugin:
  - Resolves source code for the worst-covered methods.
  - Locates one likely test file related to these classes/methods, if any.
  - Builds a large prompt describing:
    - The existing tests.
    - Each method’s implementation.
    - Guidelines for high-quality tests.
  - Sends the prompt to the Amplify Chat API.
3. Once a response is received, the **Test Recommendations** dialog appears:
  - **Recommendations** tab: AI suggestions on what test cases to add or improve.
  - **Prompt (preview)** tab: The exact prompt used to generate the recommendations.
  - You can copy the recommendations to the clipboard for reference.

---

## Configuration: Amplify Chat API

The Chat API configuration is defined in [`CoverageAIConfig`](src/main/kotlin/com/github/ronah123/vanderbilttestplugin/coverage/CoverageAIConfig.kt):

```kotlin
object CoverageAIConfig {
    const val MAX_METHODS_TO_REVIEW = 5
    const val MAX_METHOD_CHARS = 3500
    const val MAX_PROMPT_CHARS = 60000

    const val AMPLIFY_BASE = "https://prod-api.vanderbilt.ai"
    val AMPLIFY_BEARER: String by lazy { loadToken() }
    const val MODEL_ID = "gpt-5"

    const val DEBUG_SIMPLE_PROMPT = false
    const val DEBUG_SIMPLE_PROMPT_TEXT = "What is the capital of France?"
}
```

The bearer token is loaded via `loadToken()` in this order:

1. Environment variable:
  - `AMPLIFY_BEARER`
2. Local **`plugin.env`** file in the project root:
  - Simple key=value format:
    ```text
    # plugin.env (not committed to git)
    AMPLIFY_BEARER=your-token-here
    ```
3. Fallback value `"MISSING_TOKEN"` if nothing is found.

To use the real Chat API:

- Set the `AMPLIFY_BEARER` environment variable before running `runIde`, **or**
- Create a `plugin.env` file (ignored by git) with `AMPLIFY_BEARER=...`.

If no valid token is configured, API calls will fail, and you’ll see the error details in the response text.

You can also enable a simple debug mode:

- Set `DEBUG_SIMPLE_PROMPT = true` in `CoverageAIConfig` to send a fixed trivial prompt instead of the large coverage prompt. This is useful for verifying connectivity and response parsing.

---

## Code Reference

### Coverage-Related Classes

- [`AnalyzeCoverageAction`](src/main/kotlin/com/github/ronah123/vanderbilttestplugin/actions/AnalyzeCoverageAction.kt)  
  Entry point for coverage analysis, tied to the **Tools** menu.

- [`CoverageHotspotsService`](src/main/kotlin/com/github/ronah123/vanderbilttestplugin/coverage/CoverageHotspotsService.kt)  
  Project-level service that coordinates pushing coverage data into the tool window.

- [`CoverageHotspotsToolWindowFactory`](src/main/kotlin/com/github/ronah123/vanderbilttestplugin/coverage/CoverageHotspotsToolWindowFactory.kt)  
  Creates the **Coverage Hotspots** tool window and registers its panel with the service.

- [`CoverageHotspotsPanel`](src/main/kotlin/com/github/ronah123/vanderbilttestplugin/coverage/CoverageHotspotsPanel.kt)  
  Swing UI showing the table of hotspots and providing the **“Generate recommendations”** button.

- [`CodeExtraction`](src/main/kotlin/com/github/ronah123/vanderbilttestplugin/coverage/CodeExtraction.kt)  
  Utility object that:
  - Resolves `MethodBundle`s (class FQN, method name, method text) from coverage entries.
  - Selects a single likely test file.
  - Builds a combined prompt for the AI model.

- [`ChatClient`](src/main/kotlin/com/github/ronah123/vanderbilttestplugin/coverage/ChatClient.kt)  
  Simple interface with a `chatOnce(prompt: String): String` method.

- [`AmplifyChatClient`](src/main/kotlin/com/github/ronah123/vanderbilttestplugin/coverage/AmplifyChatClient.kt)  
  Concrete `ChatClient` implementation using `java.net.http.HttpClient` to call the Amplify API.

- [`RecommendationsDialog`](src/main/kotlin/com/github/ronah123/vanderbilttestplugin/coverage/RecommendationsDialog.kt)  
  Dialog wrapper that presents AI recommendations and the prompt preview in separate tabs.

- [`CoverageAIConfig`](src/main/kotlin/com/github/ronah123/vanderbilttestplugin/coverage/CoverageAIConfig.kt)  
  Central configuration for coverage/AI behavior (limits, endpoints, token-loading).

### Core Template Classes

- [`MyBundle`](src/main/kotlin/com/github/ronah123/vanderbilttestplugin/MyBundle.kt)  
  Handles localized message lookups from `messages/MyBundle.properties`.

- [`MyProjectService`](src/main/kotlin/com/github/ronah123/vanderbilttestplugin/services/MyProjectService.kt)  
  Project-level sample service logging at initialization and providing `getRandomNumber()`.

- [`MyProjectActivity`](src/main/kotlin/com/github/ronah123/vanderbilttestplugin/startup/MyProjectActivity.kt)  
  Post-startup activity that logs a reminder about sample code.

- [`MyToolWindowFactory`](src/main/kotlin/com/github/ronah123/vanderbilttestplugin/toolWindow/MyToolWindowFactory.kt)  
  Sample tool window that:
  - Displays an initial label.
  - Uses `MyProjectService` to update the label with a random number when the button is pressed.

- [`plugin.xml`](src/main/resources/META-INF/plugin.xml)  
  Declares:
  - Plugin ID, name, vendor.
  - Dependencies:
    ```xml
    <depends>com.intellij.modules.platform</depends>
    <depends>com.intellij.modules.coverage</depends>
    <depends>com.intellij.modules.java</depends>
    ```
  - The coverage analysis action under the **Tools** menu.
  - The two tool windows (sample and Coverage Hotspots).
  - The startup activity and resource bundle.

---

## Notes

This repository includes two key documents that support the analysis conducted in this project:

1. [Prompt–Response Dataset](https://docs.google.com/document/d/1m3NK7mGjNghGCPCnIksi6lZACigAT8WzbWJDHVuTR_E/edit?usp=sharing)
   This document contains the full sequence of prompts used to request AI-generated test-case feedback, along with the corresponding responses produced by the model. It serves as the raw data for the project and captures how different prompting strategies influence the quality and content of AI feedback.

2. [Evaluation and Scoring Framework](https://docs.google.com/document/d/1CKGOfI5Vv9n97BnT3IzMrvUzUU5R_HOjgaC6ywclUuc/edit?usp=sharing)
   This document outlines the quantitative methodology used to evaluate each AI response. It describes the scoring metrics (Specificity and Actionability Score, Error Detection Score, Clarity and Structure Score, and optional Brevity Ratio), explains how each score is calculated, and provides the weighting scheme used to determine the overall effectiveness of each prompt–response pair.

Together, these documents provide both the dataset and the analytical framework needed to reproduce, audit, or extend the findings of this project.


