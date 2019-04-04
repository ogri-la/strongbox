# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## 0.3.1 - 2019-04-05

### Fixed

- Fixed a bug where etag was being written before cache directory created. Wouldn't have affected existing installations.

## 0.3.0 - 2019-04-05

### Added
- added highlighted rows when an addon needs updating
- added highlighted rows to search pane to indicate installed addons
- added a context menu to installed addons pane and moved actions that target selections to it
- added special handling when installing multiple addons. It now updates gui as it works through each addon.
- both `utils/download` and `utils/download-file` now share the same user-agent when making remote calls
- user-agent now uses the wowman version stored in the project file if running from the repl, or the pom if uberjar'ed
- input text field on 'search' pane now focused immediately

### Changed
- collapsed main button bar back into single row of buttons
- 're-install all' now visible works it's way through the addons instead of just pausing indefinitely

### Removed
- removed 'update?' column in favour of highlighted rows
- removed 're-install all' button from main button bar. It can still be found under 'Addons' menu
- 'save' and 'load' settings are now only available if running debug mode

## 0.2.0 - 2019-03-30

### Added
- confirmation dialog when deleting addons
- http error handling for unsuccessful requests
- http etag support when downloading files
- checks when starting app with an unwriteable directory, or downloading an addon to an unwriteable directory.

### Changed
- quietened some log noise causing install/update/delete results to be pushed off screen

## 0.1.1 - 2019-03-24

### Fixed
- CLI "update-addon-list" action now actually updates the addon list instead of writing a useless separate file
- GUI regression in post-install/update/delete behaviour when listing addons.

### Added
- detailed contributor guidelines
- `pom.xml` generated file

## 0.1.0 - 2019-03-23

### Added
- initial release! squashed history
- a GUI and a very rough 'headless' mode
- two tabbed panes in the GUI, 'installed' and 'search'
- install, update and delete functionality for addons
- grouping newly installed addons if they unpack to multiple directories
- searching and installing from a complete list of curseforge addons
- a notice logger for operations that are happening
- logic to do the occasional large curseforge.com update and smaller incremental updates more regularly
- CI and releases with Travis-CI
