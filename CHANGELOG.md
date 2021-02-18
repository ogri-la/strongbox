# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

### Changed

### Fixed

### Removed

## 3.3.0

### Added

* installing previous releases of addons. 
    - Right-click an addon and select *Releases*.
    - curseforge and github only. wowi and tukui will only ever have a single, latest release available.
    - previous curseforge releases are limited to the most stable release of that addon by game version.
* release pinning
    - similar to 'ignore', pinning an addon at a specific release ensures the addon is not upgraded.
    - pinning an addon also prevents any grouped addons from being overwritten.

### Changed

* 're-install' will now attempt to re-install the version of the addon that is currently installed
    - if this version cannot be found in the list of available versions, then the latest version will be used.
* context menu actions now more accurately reflect what options are available.
    - unavailable actions are disabled.
    - bulk operations when many addons are selected may have different actions available.

### Fixed

* issue #229, "Limited pane size when vertically resizing window"
    - the gui wasn't positioning it's components as well as it could once vertical resolution exceeded 768px
    - thanks to @DarkVirtue for raising the issue

### Removed

## 3.2.1

### Fixed

* fixed issue where URLs of redirect responses were being normalised and the normalised URL didn't exist, causing a 404.
    - https://github.com/dakrone/clj-http/issues/582
    - thanks to @rymndhng of dakrone/clj-http for all the help

## 3.2.0

### Added

* new themes 'dark-green' and 'dark-orange'.
* a confirmation before removing an addon directory.
* aliases of a few more very popular addons that evade reconciliation.
* more (anonymous) debug information to the log file when run with `--debug` or `-v debug`.
* application icon.
    - this is very minor on Linux and macOS and does *not* affect the dock icon on macOS.
    - it also doesn't affect the icon of the standalone AppImage either.

### Changed

* refresh/F5 now clears the file cache so updates are always re-fetched.
* aliases now use `source` and `source-id` to match against the catalogue rather than `name`.
    - this was very old code that pre-dated using addon host APIs.
* import no longer skips an addon if the game-track on the addon-dir or the export record is too strict.
    - the new behaviour changes the game-track to the more lenient version. `retail-classic` if `retail` or vice-versa.
    - I think it's better to have the addon installed but set to the incorrect game track than not present at all.
* import will now use the cache.
    - might be helpful if you're attempting the same import over and over again.
* github addons now check against multiple releases for a valid asset to install.
    - rather than just the first release and fail if no valid installable asset found.
* made the distinction between colours in the dark and light themes more prominent.
    - especially the 'unsteady' colour in the dark themes that a row gets when it's being updated.

### Fixed

* a major bug affecting the new GUI on macOS *only*.
    - some kind of bad interaction between new and old GUIs caused the new GUI to quietly exit 30-60s after app init.
* a theoretical case when an entire addon directory may be deleted when importing a list of addons.
    - not as bad as it sounds, I could only recreate it under test conditions.

## 3.1.1

### Fixed

* tukui, changes to accommodate their change in API

## 3.1.0

### Added

* search results pagination
* retail and classic addons can be installed in the same addon directory, preferring one over the other
* added 'Preferences' menu
* add option to remove zip files after installation
    - `Preferences` -> `Remove addon zip after installation`
    - this is a global option, it will apply to all addon directories

### Changed

* 'import/export' menu has been moved to the 'file' menu
* 'addon' menu has been moved to the 'file' menu

### Fixed

* a case where Tukui was returning a `null` value for the `patch` field, causing an error

## 3.0.1 - 2020-10-20

### Changed

* ignored addons are no longer matched against the catalogue nor will they emit a warning about not being found in catalogue

### Fixed

* `.jar` files for macOS were not bundling the native JavaFX libraries

## 3.0.0 - 2020-10-17

### Added

* a new GUI that uses JavaFX
* a standalone distribution of srongbox that doesn't require Java to be installed

### Changed

* the default GUI to JavaFX

### Fixed

* addons with an ignored group member were not being 'un-ignored' through the GUI
    - this caused a problem when Altoholic did a buggy release with a .toc file that wasn't rendered, got implicitly ignored and then couldn't be uninstalled

### Removed

* support for the `wowman-data` repository and thus, any future updates for pre-1.0.0 strongbox

## 2.0.1 - 2020-08-11

### Fixed

* GUI bug causing the UI to hang indefinitely when adding a new addon directory
    - affected macOS users only
    - appears to have been happening with the 'import' dialogues as well, probably since always

## 2.0.0 - 2020-08-08

### Added

* addon uninstallation and uninstallation-before-installation
    - previously new addons would just unzip over any existing addons
* mutual dependency handling
    - user is now alerted when two addons depend on the same bundled addon
    - a mutual dependency is not removed until all dependent addons have been removed
* addons can now be explicitly ignored and un-ignored from the GUI
    - ignored addons cannot be uninstalled through strongbox and must be un-ignored first
    - ignored addons cannot be overwritten by other addons creating mutual dependencies

### Changed

* 'Refresh' button has been moved to the 'View' menu
* 'Addon directory' button has been moved to the 'File' menu as 'New addon directory'
* 'Remove addon directory' has been moved to the 'File' menu as 'Remove addon directory'
* the 'installed' and 'search' tab selectors under the 'File' menu have been moved to the 'View' menu

### Fixed

* the addon directory drop down is no longer width constrained
* bug where switching themes would preserve the old window
* 502 and 504 errors from Curseforge are now handled more gracefully with a custom error message
* obscure bug where selecting addons through the gui may lead to bad data being propagated through the app
* bug when selecting the game track on an empty addon directory
* bug when removing the last addon directory

### Removed

* removed support for migrating wowman-era configuration and catalogues
    - wowman-era v1 catalogues can still be read and parsed alongside v2 catalogues, strongbox just doesn't go looking for them

## 1.1.0 - 2020-06-27

### Added

* another type of code linting, provided by [Joker](https://joker-lang.org/)
* a `--debug` flag that will run strongbox with lots of output and write a log file.
    - the name of the log file is shown after the application has exited
* ticket templates with instructions on using the new `--debug` flag

### Changed

* 'wowman' was renamed 'strongbox' in [the list](https://ogri-la.github.io/wow-addon-managers/)
* split 'tukui' into 'tukui' and 'tukui-classic' in the 'source' column
    - this should make it clearer which game track a tukui addon can be installed to
* the number of addons displayed in the search results is now tuned according to number of addons in catalogue
* 'WoW Directory' has moved to the other side of the addon directory drop down and has been renamed 'Addon directory'

### Fixed

* browser not opening addon URL
* very slow searches. Typing has now been decoupled from showing search results so it feels faster.
* added a caveat against the screenshots in the README. The dark-mode version only works on particular desktops.
* fixed bug that crashes GUI when the next upgrade is available.
* support for Java 11 (the next Java LTS)
    - some date time parsing was behaving strangely between versions 8 and 11
* a very old bug where the app would continue to run when 'Quit' was selected from the menu
    - rather than closing the window or doing ctrl-c
* useragent was still stuck on 'wowman'
* tukui/elvui support was not properly distinguishing the game track leading some tukui addons to not install
* search results would highlight anything matching an installed addon's 'label'
    - this explains why the same addon from different hosts would both be highlighted despite only being installed once

## 1.0.0 - 2020-05-31

This has been a large clean up and code analyis/refactor exercise.

### Added

* users can now specify their own catalogues
* strongbox can now operate without catalogues
    - it won't do much, but it won't crash and burn
* last selected addon directory is now remembered between application restarts
* migration tasks of wowman config and catalogue and nfo files to strongbox
    - should be seamless 
    - wowman configuration is left untouched but .wowman.json files can be removed from the gui
* a 'delete .strongbox.json' action in the gui
    - behaves the same as the 'delete .wowman.json' action
* code profiling to key sections of the application I can turn on during development
    - used in optimising performance
    - disabled by default
* a log file that is written when '--debug' is used to start the application
    - intended to help hunt down user problems

### Changed

* 'wowman' changed to 'strongbox'
* all usage of 'uri' changed to 'url'
* all usage of 'catalog' changes to 'catalogue'
* user-catalogue moved to the 'config' directory from the 'data' directory
    - prevents user-catalogue being deleted with the 'clear catalogues' action
* categories are now 'tags' with common categories unified across addon hosts
* function speccing has been greatly cleaned up and is coherent and sane now
* catalogues are now on specification version 2 (see ogri-la/strongbox-catalogue)
    - version 1 catalogues (ogri-la/wowman-data) are still supported

### Fixed

* some performance problems, especially around loading catalogues

### Removed

* the relational database
    - it has been replaced with a simple (albeit large) list of addons
* last traces of a 'donation url' and 'author name' removed
* 'alt-name' from catalogue items
* 'updated date' from catalogue
* support for nfo v1
    - invalid nfo files are now deleted.
* speccing from final build
    - it is enabled during development and testing but otherwise disabled
    - key inputs like catalogues and user config are still validated against their specs

## 0.12.4 - 2020-04-22

### Changed

* clicking a link will now try the Java way of opening a URL, then look for `xdg-open`, `gnome-open` and `kde-open` to open URL
    - if nothing is found at all you will get an error in the console

### Fixed

* fixes an exception that is raised by clicking a link when Java cannot detect your 'desktop' or a means to open URLs

### Removed

## 0.12.3 - 2020-02-23

### Fixed

* fixes a bug where catalog entries whose 'description' is greater than 255 characters causes a crash while loading the database
    - thanks to https://github.com/rainecheck for reporting this bug

## 0.12.2 - 2020-02-08

* release 0.12.1 was badly formed and missing the commit with the actual fix.

## 0.12.1 - 2020-02-06

### Fixed

* addons with no nfo file were attempting to upgrade it and failing
    - these addons are now skipped
* fixed a bug where addons with a nfo file that is *still* invalid even after upgrading have the nfo file deleted

## 0.12.0 - 2020-02-03

### Added

* 'source-id' column to gui (hidden by default)
* 'game-track' column to gui (hidden by default) that is either 'retail' or 'classic'
* support for reconciling addons by 'x-curse', 'x-wowi' and 'x-tukui' ids found in the .toc file
* gui tabs can now be switched using mouse scroll
* basic support to ignore addons that appear to be under version control
    - this can be manually disabled on a per-addon basis by adding the key `ignore?` and the value `false` to that addon's `.wowman.json` file
* user catalogue (github addons) can now be exported just like a directory full of addons can.

### Changed

* renamed the 'go' column in the gui to 'source'
* export now captures game track so the correct game track is re-installed
* importing addons exported using this version of wowman should be much quicker
    - unfortunately old exports will still have an inexplicable long pause while importing. re-export your addons!
* nfo files (`.wowman.json`) are now specced out and will receive an upgrade on first start of 0.12.0
    - previously the file was only ever updated when an addon was installed or upgraded, and some bundled addons were still using very old formatted nfo files

### Fixed

* selected directory was incorrect after restarting gui by switching themes
* 'refresh user catalog' gave no indication it was doing anything until it finally re-wrote the user catalogue
* clearing catalogues and clicking refresh didn't see the database rebuilt
* importing addons that don't match the currently selected game track are still imported correctly
    - if a game track (retail or classic) could not be found, a sensible guess is made

### Removed

* dependency `data.codec`. My usage could be done with plain old java
* dependency `cheshire`. Functionality satisfied by `clojure.data.json`
* 'debugging' mode. I never used it and it wasn't doing any special

## 0.11.0 - 2019-12-31

### Added

* added www.tukui.org as an addon host
* added github.com as an addon host, using the 'releases' mechanism
    - some [examples](./github-addons.md) of supported addons
* 'user' catalogues of addons. This is a catalog controlled by the user with addons from hosts like github.com
    - this catalogue can be refreshed from `Catalog -> Refresh user catalog`
* `source` attributes are now present in the curseforge and wowinterface catalogues
* mac support
* a 'dark' theme for those Linux users who are using a dark widget set
    - this is just the inverse colours of the current 'light' theme

### Changed

* new addon directories will have their game track set to `classic` by default if `_classic_` is present in the path

### Fixed

* exporting addons using the `Import/Export -> Export addon list` now uses the currently selected addon directory
* duplicate categories in the curseforge catalogue generation
* `.part` files are now removed if an error would cause a download to fail
* if an addon directory went missing between two calls to save settings, all addon directory settings would be removed
* a regression introduced in 0.10.0 where missing catalogue fields, like `description`, would become `description=nil` after pulling it out of the database. 
    - this would cause masking of `.toc` file data that could be used in it's stead

### Removed

* special handling for `curseforge.com` HTTP redirect behaviour
    - wowman now uses the curseforge API
* dependency `metosin/spec-tools`. It's a sophisticated tool but I just wasn't taking advantage of it

## 0.10.2 - 2019-12-07

### Added

* a button that appears next to the retail/classic dropdown menu when a more recent version of wowman is available

### Fixed

* items in the catalogue that are from an unknown/unhandled source are now ignored (rather than crashing)
    - this lets me update the catalog with new sources and maintain backwards compatibility

## 0.10.1 - 2019-11-01

### Fixed

* fixes issue #83 where an addon would fail to install from the search results if the search results were unfiltered

## 0.10.0 - 2019-10-14

### Added

* the catalogue is now loaded into an embedded database for better addon searching and reduced code complexity
* support for multiple catalogues, defaulting to a 'short' catalogue with about 6k very old addons pruned from it
    - 'full', 'curseforge' and 'wowinterface' catalogues are also available to switch between
* an option to clear the catalogue cache so that they are re-downloaded
* Java Swing 'native LNF' (look and feel) for Mac users (technically unsupported)

### Changed

* switched from supporting OpenJDK 10 (EOL already) in TravisCI to OpenJDK 11 (EOL 2024)
* failure to read the catalogue (corrupt data) will see the catalogue deleted, re-downloaded and a load attempted again
    - a second failure will print an error and ask the user to choose another catalogue
* search for an addon now searches an addon's name (starting with) and description (contains)

### Fixed

* User Agent value used in HTTP requests would get truncated in releases with a minor version greater than 9
* partially downloaded files should no longer interfere with normal operation of wowman
    - files are now downloaded to temporary `.part` files and then moved into place when complete
* GUI tests were bypassing the file-system path generation that used environment variables
    - in rare cases this may have caused broken test artefacts to remain after testing or catalogues to be overwritten
* Curseforge API bug where the 'gameVersionFlavor' misleadingly indicated an addon didn't support Classic or Retail
    - this occasionally caused older versions of addons to be installed when both retail and classic were supported
* a 'download' message was not being printed as it had been downgraded from 'info' to 'debug'
    - this meant there were mysterious pauses while 'stuff' happened
    - particularly noticable on large addons or with slow connections
* default resolution was set at 640x480 which was being ignored because the contents of the frame were being 'packed'
    - packing has been removed and the default resolution is now 1024x768
* an overzealous regular expression would cause a mis-match between addons with trailing numerals in their name

### Removed

* support for partial catalogue 'updates' and scraping the recently updated pages on wowinterface and curseforge
    - with the switch to the API, this is now much faster to do with many fewer requests made
* support for scraping HTML from Curseforge
    - wowinterface still requires some HTML to be scraped

## 0.9.2 - 2019-09-21

### Fixed

* attempting to re-install an addon that is classic-only into an addon-dir that is 'retail' (or vice versa) caused a 
fault

## 0.9.1 - 2019-09-20

### Fixed

* a regular expression that stripped version information from the 'title' value in a .toc file was too aggressive and
was causing incorrect matches against the catalog.
* the GUI directory picker looks and behaves differently on a Mac if it's 'type' is 'open' rather than my custom 
'select'. The change doesn't appear to affect the directory picker on Linux at all except for the label change.
* a missing 'Title' attribute in a `.toc` file could cause the GUI to crash. The problem has been fixed as well as a 
measure to prevent individual `.toc` files from causing a crash.

## 0.9.0 - 2019-09-03

### Added

* support for multiple addon directories
    - directories can be removed by click 'remove directory' from the 'addons' menu
* support for multiple 'game tracks' (retail and classic)
* 'source-id' to the catalog. It makes for quicker and more confident matching of wowman-installed addons to the catalog
* improved test coverage.
    - tests will now fail if coverage drops below a certain threshold

### Changed

* the 'WoW' column has been re-enabled on the installed addons tab
    - many other columns are available but hidden. Use the little square to the far right of column bar to select them.
* curseforge scraping is now happening entirely from their API
* wowinterface scraping is now using a mixture of their API and HTML
* raynes/fs library has been switched to clj-commons/fs
    - RIP

### Fixed

* better handling of curseforge addon releases in general
    - not just in regards to selecting retail/classic but also skipping alpha/beta and 'alt' releases

## 0.8.2 - 2019-08-26

### Fixed

* fixed a regression introduced in release 0.8.1 where addons needing an update were not being marked as such

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
