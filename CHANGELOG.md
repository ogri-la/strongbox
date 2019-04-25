# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

* a 'go' link that will open a link to the addon's catalog page
* a simple row highlighting on the installed addons screen when moving your cursor over an addon
* errors and warnings in the notice logger are now highlighted yellow and red
* static matching of installed -> catalog addons via a simple mapping has been added for popular addons
* minor: support for highlighting unmatched addons, disabled by default, possibly not helpful at all
* a handy dandy status bar at the bottom of the screen with number of unmatched addons and total addons in catalog
* a 'help' menu with an 'about' menuitem that displays the current version, the current release, the licence and a url

### Changed

* the 'categories', 'updated' and 'WoW' columns on the installed addons screen are now hidden by default
* the 'state' directory has been split into 'data' and 'config' directories and now follow XDG recommended paths
* matching between installed addons and catalog addons has been improved, now searching across multiple joins

### Fixed

* minor: inconsistent widths between notice logger and the tables above them causing scrollbars to be misaligned

## 0.4.0 - 2019-04-15

### Added

- bad addon zip files (empty or malformed) are now removed to prevent a later cache hit
- clear cache menu option to manually clear out accumulating cache and downloaded addon zip data
- delete .wowman.json and wowmatrix.dat files actions added to clear cache menu
- columns that were being removed outside of debug mode are now simply hidden and can be toggled on/off

### Changed
- curseforge.json file is now downloaded once a day, if necessary according to etag
- previous daily cache dirs are now pruned back on app start
- highlighted row colour is no longer so close to the 'selected' row colour
- 'install selected' on search screen is now disabled until addons are selected

### Fixed
- null dates in gui were causing a strange rendering effect
- the :alt-name attribute on a sub-par match was being used to build a url that would result in a 404
- re-installing an addon with no match in catalog caused a stacktrace
- column widths on the installed addon screen no longer truncate title are reasonably sized values

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
