<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# vanderbiltTestPlugin Changelog

## [Unreleased]

## [0.0.8]

### Fixed
- Prevent repeated recommendation clicks while generation and accuracy review are running, and remind users to wait to avoid unnecessary account usage.
- Always display a readable explanation when model output is empty or contains only formatting.
- Preserve model responses when accuracy review or correction fails, clearly marking them as unverified instead of discarding them.
- Convert structured model responses into readable labels and prevent interaction-log failures from hiding the results dialog.
- Retry empty Amplify responses once with another account-authorized model and retain the working model for recommendation review; preserve immediate authentication, access, and quota failures.
- Build against the declared IntelliJ platform version by default to avoid incompatible Kotlin metadata from a newer locally installed IDE.

## [0.0.7]
### Changed
- Automatically run TestCompass and update its tool window whenever IntelliJ finishes calculating new coverage results.
- Keep `Tools > TestCompass` available as a manual analysis and refresh option.
- Include complete enclosing production source and directly referenced project classes in recommendation prompts.
- Require an internal reachability and exact-expected-value consistency check before recommendations are returned.
- Present exact expected outcomes in plain language without generating assertion code or test snippets.
- Add a second accuracy-review pass that independently recalculates expected results and verifies control-flow reachability before recommendations are displayed.
- Require novice-friendly actions with exact command sequences, grid sizes, frame counts, throw values, and bonus values.
- Validate structured recommendations locally by simulating Mars Rover commands and calculating BowlingGame scores, with at most one AI repair request when checks fail.

## [0.0.6]
### Changed
- Run TestCompass coverage analysis when the right-side tool window is opened for the first time.
- Apply test-quality requirements in recommendations without referring to a checklist or rubric.

## [0.0.5]
### Added
- Include missed source line numbers and snippets in AI recommendation prompts.
- Include up to three relevant test files when generating recommendations.

### Changed
- Generate recommendations only for coverage hotspots with missed production lines.
- Make AI guidance more coverage-specific to avoid duplicate or unrelated test suggestions.

## [0.0.4]
### Added
- Initial scaffold created from [IntelliJ Platform Plugin Template](https://github.com/JetBrains/intellij-platform-plugin-template)
