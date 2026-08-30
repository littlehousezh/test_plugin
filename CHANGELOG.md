<!-- Keep a Changelog guide -> https://keepachangelog.com -->

# vanderbiltTestPlugin Changelog

## [Unreleased]

## [0.0.7]
### Changed
- Automatically run TestCompass and update its tool window whenever IntelliJ finishes calculating new coverage results.
- Keep `Tools > TestCompass` available as a manual analysis and refresh option.
- Include complete enclosing production source and directly referenced project classes in recommendation prompts.
- Require an internal reachability and exact-expected-value consistency check before recommendations are returned.

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
