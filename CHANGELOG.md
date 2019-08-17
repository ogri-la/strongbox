# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

### Changed

### Fixed

### Removed

## 0.8.1 - 2019-08-17

### Fixed

* fixed case of a curseforge addon with no files crashing thread. a warning is issued with a link to the empty page.
* any unhandled exceptions while fetching addon information will now simply report the error and carry on

## 0.8.0 - 2019-08-12

### Added

* badly behaved addons are no longer installable
    - these are addons that bundle non-addon files, like top-level files or top-level directories with no .toc file
* a warning is now issued when an addon installs what it thinks are other 'unrelated' addons
    - these are addons like Slidebar or Stubby
    - this is troublesome because it's unexpected and the versions of these bundled addons must surely differ ...
* test coverage is now at 70%
* addon export and import

### Changed

* the catalog is now downloaded from the wowman-data 'release' files rather than a raw repository file
    - this catalog file in version control is now deprecated and will be removed in 1.0.0
* improved the zip file integrity check

### Fixed

* fixed two bugs related to catalog generation that came out of the Curseforge site update
* fixed a bug involving stale reads of the etag database causing empty files to be created

## 0.7.2 - 2019-06-29

### Added

* not part of codebase, but `wowman-data`, where wowman pulls it's catalog from, now has automated daily/weekly updates

### Changed

* Curseforge changed their website, breaking scraping. Scraping has now been tentatively marked as fixed.
* wowman release info now downloaded in background after app start so there is no delay when clicking Help -> About
* date+time manipulation is now done by `java-time` to remove a dependency and some complexity

### Fixed

* fixed bug in updating curseforge catalog where old paths to files caused a NullPointerException
* fixed bug in updating curseforge *and* wowinterface where data dirs without their catalog present would fail spec
* selected search results are now de-selected after a successful installation of addons

### Removed

* `clj-time` in favour of `clojure.java-time`

## 0.7.1 - 2019-06-23

### Fixed

* Fixed issue where a curseforge 404 response killed an async thread the GUI was using to download addon updates. 

## 0.7.0 - 2019-06-08

### Added

* wowinterface.com support. addons can now be installed from wowinterface.com just like they can from curseforge.com
* `catalog.json` is now downloaded instead of `curseforge.json`. This muxes the contents of wowinterface and curseforge.
* an 'age' field for addons. Intended to help filter really ancient addons from search results in a later release.
* better handling of `.json` files for the following cases: missing file, bad data in file, invalid data in file.
* more actions that can be called from the command line. Nothing fancy, just wowinterface/curseforge specific versions
of existing actions.

### Changed

* wowman will refuse to run as the 'root' user
* 'go' field now has a link to wowinterface.com for wowinterface addons
* slight change to addon matching rules to account for multiple catalog sources. There is now a preference for
curseforge if an addon appears in multiple sources.
* renamed `fs.clj` to `toc.clj`

### Fixed

* Application wasn't exiting (properly) when run as a jar. It would exit eventually, but not immediately.
* Paths to cache and configuration directories fixed up during testing so tests run in a more isolated environment
* Fake HTTP responses added to tests so curseforge.json/catalog.json is not downloaded while testing

### Removed

## 0.6.0 - 2019-05-08

### Added

* Arch Linux [PKGBUILD](https://github.com/ogri-la/wowman-pkgbuild) ([AUR](https://aur.archlinux.org/packages/wowman/))
* total number of downloads is now captured in the catalog and is available in the gui; visible in the search tab
* a 'spec' section to the catalog with a 'version' of '1'. I expect it to change soon and want to support older versions
* more aliases for the top 50 most installed addons. This will help with automatic matching and re-installion of addons
* added a 'go' link to the search column so you can visit the curseforge addon page in your browser
* row highlighting on mouseover in the search tab, matches the installed tab. helps the eyes trace across columns

### Changed

* improved download caching
    - etags now live in a single `$cache-dir/etag-db.json` file versus individual `.etag` files
    - http request is not even made if a filesystem cache hit occurs and the file is still fresh (24hrs by default)
    - pruning cache files now prunes the old 'daily' cache directories but also based on last modification time.
* addons in catalog are now ordered alphabetically by their keys. I was relying on an implementation detail previously

### Fixed

* installed addons are refreshed after `.wowman.json` files are deleted
* column widths in the search tab got some TLC

### Removed

* the `daily-cache-dir`. cache files now expire based on their last modification time.

## 0.5.0 - 2019-04-25

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
