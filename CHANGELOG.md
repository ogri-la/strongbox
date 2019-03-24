# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Fixed
- CLI "update-addon-list" action now actually updates the addon list instead of writing a useless separate file

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
