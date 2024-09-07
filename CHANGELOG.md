# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## 7.5.0 - 2024-09-07

### Added

* added another heuristic for guessing which asset in a Github release supports retail.
    - in this case, if there is a single remaining unclassified asset, and there are other classified assets and nothing has been classified as retail yet, classify that asset as retail.
        - yes, this is guessing, and it won't always be correct, but it's probably true most of the time.
    - thanks @jake770321
* error handling for a few cases where uncaught exceptions were being swallowed.

### Changed

* installing an addon by file now overwrites/updates ignored addons and unpins pinned addons.
    - this accommodates a use case where Strongbox is used to install a select few addons manually from unsupported sources (like Curseforge) but then the new addons match against older versions in the catalogue from wowi.
    - please open a feature request if you want this behaviour adjustable.
* updated internal constants for The War Within.
* updated internal constant for the date of the previous expansion.
    - affects how much the emergency 'short' catalogue gets truncated from the 'full' catalogue.
        - emergency catalogue is used when Github hosted catalogue is unavailable, which is rare.
* trimmed the '(Flatpak)' from the 'Strongbox (Flatpak)' on the `.desktop` files distributed by Flatpak.
    - thanks @deadmeu

### Fixed

* fixed bug in emergency 'short' catalogue generation.
    - it would include addons from BFA instead of stopping at Shadowlands.
* fixed bug where installing a file manually would bypass ignored and pinned addon checks.
    - it would tell you it was refusing to install, then install it anyway.
    - thanks @akanouras
* fixed bug where ignore check bypassed on ungrouped/single directory addons.
    - logic assumed all addons were grouped, but grouping only happens when an addon unzip to multiple directories.
    - pretty big bug!

## 7.4.0 - 2024-07-19

### Added

* added support for parsing multiple interface version values from .toc file.
    - a single .toc file may now yield multiple possible game tracks (retail, classic, wrath, etc).
* added entries to the known patch list for the recent major Dragonflight patches and The War Within.

### Changed

* the WoW column in the GUI now supports displaying multiple game versions (like 8.0, 9.0, 10.0).
    - these are derived from interface versions (80000, 90000, 100000).
* the raw data GUI widget in the addon details pane now supports displaying multiple interface versions.
* replaced references to the old wiki with the new wiki (warcraft.wiki.gg).
* bumped dependencies.

### Fixed

* fixed a typo in a field name in the import/export logic.
* fixed a bug in the interface-version => game-version logic where 110000 was returned as '1.0.000' instead of '11.0.0'.

### Removed

* removed curseforge and tukui test fixtures and the fetching of test fixtures.
* removed support for 'release labels' attached to addon source updates.
    - only supported by curseforge and provided a friendlier label for a release than a version number.
* removed more curseforge and tukui logic that was disabled many releases ago.
* removed support for interface versions in .toc files containing non-numeric values.
    - for example, "30008a" which used to parse to "3.0.8a".
    - the interface version for these alpha and beta releases don't actually have 'a' and 'b' suffixes.
        - https://warcraft.wiki.gg/wiki/Patch_3.0.8a
        - https://warcraft.wiki.gg/wiki/Patch_8.1.0a

## 7.3.0 - 2024-04-23

### Added

* added support for Cataclysm Classic.

### Changed

* dependencies bumped to latest versions.
* game track priority when 'strict' is off now has a proper and consistent strategy.

### Fixed

* bumps Clojure from 1.10 to 1.11.2 to fix CVE-2024-22871
    - https://github.com/advisories/GHSA-vr64-r9qj-h27f
* an Arch Linux AUR bug introduced with pacman 6.1 that stripped the binary down to nothing.
    - thanks to @nickromanooo for reporting the bug and tracking down the pacman issue.

## 7.2.0 - 2023-12-24

### Added

* `x-website` Github URLs in `.toc` files are now treated as a Github source that an addon can be switched to.
    - when both `x-github` and Github `x-website` sources exist, `x-github` takes priority.

### Changed

* moved AppImage building to `ogri-la/strongbox-appimage`.
* consolidated all the `.sh` into a single `manage.sh` script.
    - `lint.sh` moved to `manage.sh` as command 'lint'.
    - `run-tests.sh` moved to `manage.sh` as command 'test'.
    - `update-test-fixtures.sh` moved to `manage.sh` as command 'update-test-fixtures'.
    - `build-linx-image.sh` moved to `ogri-la/strongbox-appimage` as `build-appimage.sh`.

### Fixed

* fixed bug preventing classic Wrath `.toc` from being detected and loaded.
* fixed bug in catalogue location spec where a value that should have been validating as a URL was not.
* removed `x-tukui-id` source detection in `.toc` files. Tukui no longer hosts addons so these can't be used.
    - overlooked, should have been part of 7.0.0 major version.

### Removed

* removed `release.sh` as it had been unused for several years.

## 7.1.0 - 2023-11-26

### Added

* added checkbox to bottom of search tab that will toggle search results sampling when search results are unfiltered.
    - this lets you browse a catalogue page by page.
* added patch tooltips for Dragonflight 10.1 and 10.2
* added support for the `NO_COLOR` environment variable.
    - it turns off coloured output in the console if that is where you launched strongbox from.
* added new command line action `print-config` that prints the final config and exits.
    - usage: `./strongbox --action print-config`
* added new information captured from the environment when using `--debug` or `--action print-config`.
    - `state.paths.config-dir`, path to where Strongbox is storing the application *config*.
    - `state.paths.data-dir`, path to where Strongbox is storing the application *data*.
    - `flatpak-id`, looks like `la.ogri.strongbox` when running within a Flatpak.
* added plain-text and JSON views of the raw addon data to the 'raw data' widget on the addon detail tab.
    - it's pretty basic but JavaFX really resists being able to select text within it's widgets.
* added new preference 'check for update' that toggles checking for an update to Strongbox on startup (default is `true`).
* added new command line flag `--update-check` and it's complement `--no-update-check` that toggles update checks.
* added Flatpak distribution hosted by Flathub
    - see: https://flathub.org/en-GB/apps/la.ogri.strongbox

### Changed

* bumped JavaFX dependencies from 17.x to 19.x.
* removed the fixed widths on the 'updated' and 'downloads' columns in the search pane.
    - attempting to control column widths in a JavaFX table is futile.
* some `info` level logging has been dropped to `debug` to reduce console noise.

### Fixed

* tag buttons in search pane are now centred vertically.
* selecting hosts in search pane no longer samples results.
* 'interface version' was being treated as an integer and being localised in the raw data widget.

## 7.0.0 - 2023-06-11

### Added

* added 'stats' that serves to centralise a bunch of numbers used internally that might be interesting to the user.
    - see the `more stats` button in the bottom left corner.
* Github rate limit information is now fetched as part of the stats.
    - but no more than once a minute.
    - see: https://github.com/ogri-la/strongbox#user-content-github-api-authentication
* manually refreshing the user catalogue will now switch to the `log` pane before doing so.
    - the intent is to show that *something* is happening.
    - see: `Catalogue -> Refresh user catalogue`
* new (opt-in) preference to automaticaly refresh the user-catalogue every 28 days.
    - the user-catalogue are addons added to strongbox using `File -> Import addon` or by 'starring' a regular addon.
    - see: `Preferences -> Keep user catalogue updated`.
* added a "clear" button to the addons search that removes all search filters, including search terms.
* added new column for installed addons "starred" that will add an installed addon to the 'user-catalogue'.
    - star button disabled when addon is being ignored or isn't matched against the catalogue.
* new column for installed addons "size" with the total size of the addon on disk, including any grouped addons.
    - see: `View -> Columns -> size`
* `user.clj`, where the REPL will take you by default during development.
    - this lets me separate some development dependencies and logic from what is released.

### Changed

* the main window is now always split with the bottom pane hidden by default.
    - if can be dragged open or toggled open by either of the two status bar buttons.
* clicking the status bar buttons to open the bottom pane is now much quicker.
* refreshing the user-catalogue now checks imported/starred addons against the full catalogue before checking online.
    - if it fails to find addon in catalogue, it will fall back to checking online like before.
    - the user-catalogue was originally for imported addons without a catalogue (github, gitlab) but is now also for 'starred' addons. Refreshing it now that we have a Github catalogue is much faster.
* menu labels for the installed addons table columns have been tweaked (see `View -> Columns`)
    - "installed" is now "installed version"
    - "available" is now "available version"
    - "version" is now "installed+available version"
    - "WoW" is now "game version (WoW)"
* the "fat" column profile now uses the "installed" and "available" columns rather than the combined "version" column.
* the "fat" column profile includes the new "starred" and "size" columns.
    - see: `View -> Columns -> fat`
* `jlink compress=2` changed to `jlink compress=1` during the building of the linux AppImage.
    - `2` means "zip", which interferes with the final AppImage compression.
    - this shaves off ~7MB from the final AppImage.
* replaced the compressed, static, "emergency" catalogue with a simple JSON string.
    1. it wasn't working at compile time like I thought.
    2. regular strings are more compressible ultimately when building an AppImage.
* bumped dependencies.
    - removed `apache.commons.compressors` as no longer required.
* some dependencies used for development are no longer bundled during release.
* strongbox release data will only be downloaded once the app has finished loading.
* strongbox release data will only be downloaded once per-session.
    - it would previously re-attempt to download the release data on failure endlessly.

### Fixed

* search tab filter buttons are now a uniform height.
* `key` column in the addon detail "raw data" widget is now wide enough for the text "supported game tracks".
* `updated` column in the installed addon tab is now wide enough for the text "12 months ago".
* possible cache stampede fetching strongbox release info. A lock is now acquired to ensure checks happen sequentially.
    - it was possible for the GUI to fire off many requests to Github simultaneously, bypassing cache and overwriting each other.

### Removed

* Tukui support. Tukui addons are:
    - no longer checked for updates.
    - excluded from being imported.
    - excluded from the user-catalogue.
    - no longer scraped from the tukui.org API into a catalogue.
    - no longer present in the "full" or "short" catalogues.
    - excluded from search results.
    - removed from the 'emergency' (built-in, hardcoded) catalogue (used when remote catalogues are unavailable).
    - removed from lists of available addon hosts to switch an addon between.

## 6.1.2 - 2023-05-16

### Fixed

* issue #402, fixed a freezing bug in the search results, introduced in 5.1.0 (2022-03-02).
    - if the 'full' catalogue were selected, searching and selecting a result would freeze the GUI.
        - it may possibly have disabled mouse events as well, depending on your window manager.
    - No definite cause found, however the bug was quacking suspicously like a deadlock/livelock.
    - Thanks to @sergen213 for their help and feedback.

## 6.1.1 - 2023-05-07

### Fixed

* bad or missing 'content-length' HTTP header fields are now handled without errors.
* the "All" patch level returned by Tukui is now supported when checking the updated details of an addon.

## 6.1.0 - 2023-03-19

### Added

* filtering search results by tag can now be switched between inclusive ('any of', default) and exclusive ('all of').
* embedded font for glyphs used in the uber and star buttons for systems running with minimal fonts.
    - https://github.com/ogri-la/strongbox/issues/384
* an app launcher to desktop environments that support them.
    - for me, `strongbox` was found under the `Games` menu item.
    - only for installations via the AUR
        - the `.desktop` file can be found here: https://github.com/ogri-la/strongbox-pkgbuild/blob/master/strongbox.desktop

### Changed

* search results filtered by tag are no longer sampled when no search input is available.
    - this means you can now browse addons by tags alone if there are more addons that can fit on a single page of results.
* glyphs used for the uber and star buttons switched to their close equivalents in the new embedded font
    - they're a little chunkier now but also look a little better in my estimation.
* moved some files used for building the AppImage from the root into a directory called `AppImage`.

### Fixed

* page >1 of search results that were then filtered further may have displayed the 'mascot' screen (ᕙ[°▿°]ᕗ)
    - this was because the pagination wasn't being reset and there was no 'page N' for the given set of filters.
    - the 'mascot' screen is still (deliberately) displayed in rare cases.
* characters in multi-line messages in the notice logger no longer get their descenders ('y', 'g', 'p', etc) truncated.
* ignored addons in the addon detail pane now display mutual dependencies (if any).
* 'WoW' column text was black with a dark background in dark mode, making the value illegible.

## 6.0.0 - 2022-11-10

### Added

* support for six digit interface versions.
    - Dragonflight is `100000`
* 'Dragonflight' tooltip for 10.0 addons.

### Changed

* the slugify function used on a toc's 'title' value switched to the same one used when building catalogues.
    - the 'title' value used to be more important as it was primarily used to match an addon within a catalogue.
        - it's more of a fallback these days if the addon's `source` and `source-id` aren't found in the catalogue.
* reviewed a lot of code, lots of very minor shuffling and tweaks that won't affect the user at all.
    - unless of course it breaks something.
    - I was taking advantage of the major version bump to do some unnecessary and hard to justify changes.
        - plenty more could still be done but I'll tackle them individually.

### Fixed

* fixed an issue with WotLK toc data having no priority in non-WotLK game tracks.
    - for example, if an addon directory is using the 'Classic TBC' game track and an addon had 'Classic WotLK' toc data but no TBC toc data, the WotLK toc data would be ignored when it *should* be preferred over retail toc data.

### Removed

* removed support for building catalogues.
    - this logic now lives in `ogri-la/strongbox-catalogue-builder`.
* removed the command line actions:
    - 'scrape-catalogue'
    - 'write-catalogue'
    - 'scrape-github-catalogue'
    - 'scrape-wowinterface-catalogue'
    - 'scrape-tukui-catalogue'
* removed support for reading version 1 catalogues built using `wowman` 
    - last release of `wowman` was 2020-06-01
* removed the 'tag' logic used to normalise and map categories between addons and addon hosts.
    - it was used exclusively for catalogue building and this logic now lives in `ogri-la/strongbox-catalogue-builder`.
* removed dependencies `org.clojure/data.csv` and `enlive`.
* removed support for finding `wowman`-era config files.

## 5.4.1 - 2022-09-11

### Added

* support for Tukui's WotLK addons
    - because of how Tukui partition their addons they need some special handling.

### Changed

* inconsistent 'source' column widths and resizing behaviour in the search addons tab.

### Fixed

* bug with selecting the 'WotLK Classic' game track in strict mode that would empty the list of installed addons.
* minor bug with an error message repeated N times when installing an addon and the selected game track isn't supported
    - affected non-strict mode only and was simply the same error message but for each game track it iterated through, i.e. 'no releases found for classic', 'no releases found for classic-tbc', etc.

## 5.4.0 - 2022-08-21

### Added

* support for WotLK Classic.
    - you can now select the game track from the drop down, strictly enforce it or not, etc.

### Fixed

* the WoW column tooltip occasionally received a map of data instead of the addon's supported WoW version during rendering.

## 5.3.0 - 2022-07-09

### Added

* multiple addons can now be selected and installed from `File -> Install addon from file`.
* tooltips for values in the 'WoW' column are now the long-form patch name.
    - for example, '9.2.5' will have the tooltip 'Shadowlands: Eternity's End'.

### Changed

* addons that are completely replaced by another addon are now uninstalled first rather than creating a 'mutual dependency' (overwritten in-place with a reference).
    - using the `Find similar` option to replace Curseforge addons was leading to addons from other hosts creating unnecessary mutual dependencies.
    - also common when installing an addon from a file that overwrites one installed from a catalogue or vice-versa.
* data from invalid .toc files is now discarded.
    - for example, Questie: https://github.com/Questie/Questie/blob/2242b806172163122ba0a08e451b750851aac3f8/Questie.toc#L4
    - it means some data derived from toc files, like description, game-track, alternate source hosts, etc will not be available.
    - if there is a catalogue match then most of this shouldn't be a problem. This is a pretty rare case.
* `ctrl-f5` now clears the http cache and then does a full refresh, including a reload of the addons from the filesystem.
    - previously it just cleared the cache and checked for updates online.
* some warnings have been made less obscure or dropped down to debug statements.
    - start strongbox with `--debug` to see debug-level messages.

## 5.2.0 - 2022-06-04

### Added

* added 'scrape-github-catalogue' to the list of command line actions.
* added 'Browse addon directory' to the 'File' menu that opens your configured file browser to the selected addon directory.
    - also accessible with the `ctrl+b` shortcut.
* added ability to install addons from zip files, bypassing catalogue matching.
    - you get grouping, ignoring and pinning but unless the addon matches against the catalogue, you won't get updates.
    - see: `File -> Install addon from file`

### Changed

* bumped JavaFX from 15.0.1 to 17.0.2
* addons are now installed and uninstalled in parallel.
    - previously they were just downloaded in parallel then installed sequentially to prevent mutual dependencies potentially overwriting each other in a non-deterministic way.
* the warning message 'addon "Foo" is overwriting "Bar"' when creating a mutual dependency has been changed to the more helpful '"Foo" (1.2.3) replaced directory "Bar/" of addon "Baz" (3.4.5)'.
    - or similar. It varies depending on availability of version numbers, labels, etc.
* 'version' column on installed addons tab now emboldens it's value when update available.
    - both 'installed version' and 'available version' columns are also available if you prefer separate values.
    - (possibly not working on mac)
* 'updated' column on the search tab is now 'friendly' formatted using a relative time label.
    - consistent with the date columns on the installed addons tab.

### Fixed

* fixed handling for empty game track lists in the github csv catalogue.
* fixed bug where the GUI would look for parallel jobs matching a complex ID when jobs with a simple ID were present.
* fixed placeholder text size in addon detail pane for addons with no mutual dependency data (like search results).
* fixed button text in addon detail pane from "install" to "re-install" for releases matching currently installed version.

## 5.1.0 - 2022-03-01

### Added

* 'star' column to the search tab. Starring an addon will add it to your user-catalogue.
* 'star' button to the search pane that will filter addons to those in your user-catalogue.
* 'addon host' multi-checkbox field to the search tab, allowing you to select which hosts to see addons from.
    - disabled when there is only one host to choose from.
* clickable tags in the 'tags' column that filter search results.
    - selected tags appear in the search area. clicking them will remove the tag from the search.
* gui, search, count of addons selected to the 'install selected' button.
* gui, addon-details, a 'mutual dependencies' pane to show which addons the current addon is overwriting.
* gui, addon-details, the installed and latest releases are now highlighted in the 'releases' pane.

### Changed

* core, the user-catalogue is now part of application state rather than read from file as needed.
* gui, search, 'install selected' button moved to the left of the search field and disabled if no addons selected.
* gui, addon-details, switched from a three-pane layout to a two-pane layout with navigation.
    - default panes displayed are 'releases' and 'grouped addons'
    - 'raw-data' shifted to it's own pane, it's always been a bit of a debug option.
* gui, styling, some of the early, complex, styling has been minimised and contained to just the install and search tabs.
    - the appearance shouldn't have altered, it's just less wooly behind the scenes.
* gui, addon-details, very minor, "Stop ignoring" label changed to "Unignore" to conserve horizontal space.
* gui, installed addons, 'update all' button no longer does anything if no addons need updates.
* gui, the 'split' button (bottom right) is now a proper 'toggle' button with indent and outdent styling.

### Fixed

* gui, addon-details, very minor bug that would sometimes result in two seemingly identical addon tabs being open.
    - new tab and their default state were being compared to all other tabs open and, if not found, a new one would be opened.
        - for example, if you changed the log level in an addon's detail pane then opened the addon again, you'd get a duplicate tab.
* gui, addon-details, the most recent release would always be missing from the 'releases' pane.
    - my thinking was the user wouldn't want to see the latest release, only the *other* releases available.
* gui, installed addons, removing all columns will now present with you a helpful message again and a button to reset columns.
    - the condition that would trigger this message was never being hit after the introduction of the 'arrow' column in `4.7.0`.

### Removed

* the 'random' button. room was needed for the new filters.
    - it's affect can still be simulated by pressing spacebar in the search field.
* the 'wrote: /path/to/user-catalogue.json' message when the user catalogue is updated.
    - it's just noise now that the operation has become common.

## 5.0.0 - 2022-01-31

### Added

* column presets 'default', 'skinny' and 'fat'.
* support for `X-Github` fields in the `.toc` file.
    - these are a form `x-<source>-id` like `x-curse-project-id` and `x-wowi-id` and the value should be a Github URL.

### Changed

* replaced 'View -> Columns -> Reset to defaults' with the 'default' column preset.
* github catalogue tweaks to support latest version of layday's `github-wow-addon-catalogue`.
    - see: https://github.com/layday/github-wow-addon-catalogue
* column preferences in the config file are now upgraded to the new default column set.
    - any customised column preferences are preserved.
* minor, errors/warning/info messages that appeared across multiple messages are now just one message.
    - I also replaced some developer-isms like 'unhandled exception' with 'unexpected error'.
        - please let me know if you ever find messages in the log obscure.
* curseforge February deprecation notice is now the definitive "no longer supported".

### Fixed

* minor, clicking an already selected source in an addon's 'Source' context menu no longer triggers an update.

### Removed

* Curseforge support. Curseforge addons are:
    - no longer checked for updates.
    - excluded from being imported.
    - excluded from the user-catalogue.
    - no longer scraped from the api into a catalogue.
    - no longer present in the 'full' or 'short' catalogues.
    - excluded from search results.
    - removed from the 'emergency' (built-in, hardcoded) catalogue (used when remote catalogues are unavailable).
    - removed from lists of known addon sources to switch between.

## 4.9.1 - 2022-01-20

### Fixed

* github, regression, pre-releases and drafts are now removed from consideration when looking for updates.
* addon, regression, the 'updatable?' check now correctly handles addons with multiple .toc files.
    - regular addons were being affected by some buggy logic here introduced in 4.8.0 (2021-12-12).

## 4.9.0 - 2022-01-02

### Added

* a github catalogue.
    - actually, the catalogue is available right now if you're using the 'short' (default) or 'full' catalogues.
    - an entry in the `Catalogue` menu where you can switch to the Github catalogue exclusively.
* source switching. 
    - when an addon has multiple sources (curseforge, wowinterface, github, etc) you can switch between them.
        - this relies on the `x-project-id` type values in addon `.toc` files.
    - a 'Source' context menu option for addons with multiple sources.
    - a 'other sources' column with clickable links.
        - note: curseforge links are a bit hit or miss as a direct URL isn't possible from just the source/project ID
* 'find similar' context menu option. It searches the catalogue for addons that share the addon's name.
    - this *may* reveal the same addon on different hosts that aren't present in an addon's `.toc` file.
        - ensure the 'short' or 'full' catalogues are selected for better results.
* a warning for curseforge addons that updates will stop Feb 1st 2022 and directions on how to migrate.
* new dependency `org.clojure/data.csv` for parsing the Github catalogue.

### Changed

* the set of catalogues in your `config.json` file will be upgraded to include the new Github catalogue
    - but only if it looks unmodified from the default set of catalogues.
* curseforge addons missing from the currently selected catalogue can no longer be checked for updates.
    - this was a recent feature that allowed addons that could patch together a full set of data to be checked regardless of a match in the catalogue.
        - it is now disabled for curseforge because the `group-id` value it depends on may not be consistent now that source switching is present.

### Fixed

* http backoff regression on timeout errors.
    - the 'synthetic' http errors I was using to replicate socket connection errors and connection timeout errors used 4xx statuses.
        - this meant they were considered 'client' errors (404, 403, etc) and were *not* re-attempted.
* minor issue causing cramped multi-line messages in the notice logger.

## 4.8.0 - 2021-12-12

### Added

* Gitlab support.
    - similar to addons hosted on Github, Gitlab addons must be using releases with custom assets (not the default ones).
* multi-toc support.
    - the presence of multiple .toc files determines if an update is necessary when switching game tracks.
    - a warning is issued if the suffix of a `.toc` file doesn't match the `Interface Version` within it.
        - for example, `SomeAddon-Classic.toc` is using a `20501` as it's interface version.
        - this may lead to false positives for the few addons that are named `SomeAddon-Classic` and use multiple toc files.
    - a warning is issued if there are multiple non-identical sets of toc data for the same game track.
        - for example, an addon has `SomeAddon-Classic.toc` and `SomeAddon-Vanilla.toc` (both valid), but `-Vanilla.toc` data is not identical to `-Classic.toc`.
            - in these cases the first in the group is used with '-Classic' having alphabetic priority over '-Vanilla', '-BCC' over '-TBC', etc.
* `release.json` support for Github and Gitlab.
    - this file is generated by Bigwigs packager and includes extra information about files in a 'release'.
        - a 'release' is a bundle of files, including the addon zip file, in Github and Gitlab attached to a revision tag.
    - it is being used to find the supported game tracks for a release if they can't be guessed in any other fashion.

### Changed

* the 'source-id' column in the GUI has had it's max width removed.
    - this is because Gitlab addons can have very long IDs.
* Github addons are no longer assumed to be for retail/mainline unless released prior to the release of Classic.
* Github addons will now do extra checks to determine game tracks for the latest release, on initial import and during the user-catalogue refresh.
    - if absolutely no game tracks can be found we default to retail. 
        - this may be replaced in the future by actually downloading a release and inspecting it's contents
* http downloads will no longer be re-attempted on client (4xx) errors, server (5xx) and connection errors only.
    - this fixes the multi-second pause before an error while updating addons that have gone away.

### Fixed

* a minor bug in translating an interface version (10100) to a game track ("classic").
    - a bad value might have caused a stacktrace in the console
* stability improvements to the 'refresh user catalogue' feature.
    - 404s, transient server errors and unhandled exceptions will no longer stop the update process.

## 4.7.0 - 2021-10-31

### Added

* expanding rows for grouped addons.
    - clicking the arrow at the far left of the screen will 'expand' the addon and display the set of grouped addons.
    - these addons were bundled together with the addon that was installed but are otherwise hidden behind the 'primary' addon.
        - the 'primary' addon is just a best-guess at which the main addon of the group is.
    - actions like 'ignore', 'pin', 'uninstall' etc can't be performed against these grouped addons.
* columns in the GUI can now be toggled on and off and reset back to their defaults.
    - these preferences are preserved between application restarts.
* new columns 'source-id', 'tags', a combined 'version' column, 'browse local files' and friendly 'created' and 'updated' columns.
* handling for hosts that are refusing connections rather than accepting connections but then timing out.
    - happened with Tukui the other day.
* most http requests now make two more attempts to download html/json before giving up.
    - curseforge is a bit flaky when making bursts of requests in parallel and we're hitting the 5sec timeout more often.

### Changed

* status button now has 'ignored' (empty circle) and 'pinned' (filled circle) icons instead of nothing and a tick respectively.
* connection timeouts are now reduced from 8sec to 5sec.
* minor, `File -> Export Github addon list` renamed `File -> Export the user-catalogue`.

### Fixed

* ignored addons had an incorrect 'updated' date.
    - previously only visible on the addon detail page, a dummy 'polyfilled' date of 2001-01-01 was being used.
* context menu not refreshed after performing an action and then immediately right clicking the addon again.
    - for example, ignoring an addon and then right clicking you would see the 'ignore' option still available.
* connection timeout handling (introduced `4.3.0`) has never worked outside of test conditions.
    - it's because certain configuration is ignored if the more advanced `RequestConfig` is present.

### Removed

* the inability to resize certain columns. All columns can be resized now, although some still have maximum widths.

## 4.6.0 - 2021-10-01

### Added

* a `--version` parameter that prints the name of the app and it's current version.
* an 'emergency' catalogue that strongbox can use if the remote catalogue is not available.
    - it's a bz2 compressed `full-catalogue.json` that was available at build time.
    - the trade-offs are it's older, makes strongbox binary a little larger and switching catalogues a little slower.

### Changed

* bumped dependencies
    - slight increase in performance loading JSON with a newer version of the `data.json` library.
* searching for addons is now much *much* faster.
    - matching search input against an addon has been improved by two orders of magnitude.
* searching now searches within the addon name rather than from the beginning of the name.
* catalogue is no longer validated when reading the catalogue JSON file.
    - it was the major cause of slow catalogue loads, switches between catalogues and (probably) overly cautious.
    - a well-formed but invalid catalogue would cause a freeze printing the validation error to the console.
* addon maps no longer have their keys ordered on reading.
    - a minor cause of slow catalogue loading/switching and only necessary when creating catalogues.
* gui, context menu options disabled when no addons selected.

### Fixed

* case where selected addons persist across addon directory changes.
* warning on startup about `cat` already refering to `clojure.core/cat`.
* case where a wowinterface addon pending approval couldn't be downloaded because of a missing query parameter.
* regression where a bad addon downloaded in parallel would still be passed to the 'install addon' operation.
* typo causing a failure to properly validate the 'created-date' in the catalogue.
    - this affected both reading and writing catalogues however catalogues are no longer validated on read.
* attempting to check an addon from an unknown host would cause an unhelpful error.
* attempting to install an addon with an unknown game track would cause an error.
* installing an addon in certain circumstances assumed a successful check for updates had been made.

### Removed

* the `--profile` command line option and the scaffolding around integrating it with the tests and test coverage.

## 4.5.0 - 2021-09-05

### Added

* better error handling when no network connection is available.
* a warning when the update available is from a different addon host.
    - for example, an addon is removed from curseforge and now receives updates on wowinterface exclusively.

### Changed

* addons that fail to match against the catalogue but have matched against a catalogue previously can now be updated.
    - for example, addons in these cases are now updateable: 
        - an addon doesn't get updates any more but still works and is eventually removed from the 'short' catalogue.
        - you decide to go curseforge-only and have that one addon from wowinterface you can't live without (or vice-versa)

## 4.4.1 - 2021-08-19

### Changed

* status bar catalogue count is now localised.
* the 404 error displayed from Curseforge is now more helpful than simply 'not found'.

### Fixed

* fixed bug preventing installation of addons from the search pane.
* sorting by 'downloads' in search now works as expected.

## 4.4.0 - 2021-08-17

### Added

* the operations 'check for update' and 'download addon' now run in parallel.
    - operations run `n` at a time, where `n` is the number of cores your machine has.
    - the 'install addon' operation still happens serially to preserve mutual dependency checking.
* a progress bar that (temporarily) replaces the status button when parallel operations are happening.

### Changed

* installing a specific addon version no longer blocks the GUI while the addon is downloaded and installed.
* cache duration of textual files is now 1hr rather than 24hrs.
    - a 'hard refresh' to clear the cache and re-fetch addon details is still possible using alt-F5.
    - zip files are still stored indefinitely until manually cleared or the 'Remove addon zip after installation' preference is set.

### Fixed

* fetching strongbox release data while over (GitHub) quota no longer results in an infinite log loop.

### Removed

* row background colour for 'unsteady' addons (updating/downloading/being modified) has been removed.
    - it's too frenetic when multiple addons are doing things in parallel.

## 4.3.0 - 2021-07-01

### Added

* addons from Curseforge, wowinterface.com, Tukui and Github can now be imported from URLs rather than the catalogue.
    - `File` -> `Import addon`
    - imported addons are added to the 'user-catalogue' and available for installation regardless of selected catalogue.
        - the user-catalogue lives here: `~/.config/strongbox/user-catalogue`
* Github authentication using Github Personal Access Tokens
    - if the environment variable `GITHUB_TOKEN` is present, it is used to authenticate with Github for API calls.
    - remaining number of API calls available can be seen in the strongbox log tab.

### Changed

* `Import addon from Github` from the `File` menu has been renamed `Import addon` and is now the first entry.
* `Catalogue` -> `Refresh user catalogue` will now refresh addons in the user-catalogue from all addon hosts.
    - for non-Github addons, updated addon data is pulled from the currently selected catalogue.
* HTTP requests now have an 8s timeout.
    - if it takes longer than 8s to make a connection or receive data, then the request is cancelled and an error logged.
* search, very minor, but trailing whitespace in a search term is now ignored.

### Fixed

* HTTP "User-Agent" is now set correctly.
    - I don't think it's ever been working correctly. Total failure.
* search results are now updated when the catalogue is switched and the search term, if any, is re-searched for.
* Curseforge API has capped the number of results per-request to a max of 50

## 4.2.0 - 2021-06-02

### Added

* 'report' level log messages.
    - used to break up and give context to repetitive blocks of log messages.
    - just 'refresh' for now, logged whenever the list of addons are re-read.
* warnings and errors generated while installing an addon are now captured and displayed in a dialog box.
    - partially addresses points raised in https://github.com/ogri-la/strongbox/issues/231
* a warning when 'strict' is unticked and an update is available for a mismatched game track.
    - for example, a 'classic' release is available for an addon but the addon directory is set to 'retail'.
* an 'install' button on the search pane to install addons individually.
    - ... which is probably what *most* of us want *most* of the time.
* a 'split' button that brings up the notice logger at the highest current log level.
    - I missed the old split pane layout and decided the "(warnings)" suffix in the log tab title wasn't good enough.
    - it's yellow with warnings, red when errors.
    - split doesn't persist between app restarts. If you want to see this let me know.
* another variation of the burning crusade abbreviation 'bcc' for the github game track guesser to look for.
    - it seems to be gaining traction thanks to BigWigs packager but was non-existant a month or so ago.

### Changed

* GUI now disables some actions when no addon directory is selected.
* the game track (retail, classic, classic-tbc) + version (1.2.3) is now used to determine if an addon is updateable.
* 'report' level log messages now appear in an addon's notice logger.
    - used to break up and give context to repetitive blocks of log messages.
* tweaks to error/information dialog panes so messages are easier to read.
    - asterisks `*` and hyphen `-` for list bullets have been replaced with a round black dot `•`
* the 'downloads' column on the search tab are now formatted according to your locale.
    - for me, this means long numbers are now comma separated.
* the 'updated' column on the search tab is now centred.
* the 'installed' and 'available' columns are now right aligned with ellipses appearing on the left when truncated.
    - I'm 50/50 on this, let me know if you feel strongly one way or the other.
* an addon's status (green tick, yellow line, red cross, etc) is limited to just events since the last refresh.
* 'install' buttons in the search and addon detail panes are slightly less Weird looking.

### Fixed

* a bug where refreshing the 'user' catalogue would throw an error if a repository no longer existed.
* a case where hitting 'update all' many times quickly could cause unexpected behaviour and errors.
    - it will now issue a warning that addons are being updated.

### Removed

* github addons will no longer have a `-classic` or `-classic-tbc` suffix.
    - this was added to differentiate two releases using the same name and version but different game tracks.
    - strongbox will mark affected addons as being updateable because the versions no longer match. The same version will be re-installed.

## 4.1.0 - 2021-05-09

### Added

* a 'welcome' screen for instances of strongbox with no addon directories selected.
* added `Classic (TBC)` to selectable game tracks and the `Strict` checkbox to toggle between strictness
* support for `Classic (TBC)` for wowinterface, curseforge, tukui and github.
* github api now considers the name of the *release* when attempting to detect the game track of an asset.
    - priority is now: asset name, release name and then any game tracks detected in .toc file

### Changed

* replaced the 'any, prefer retail' and 'any, prefer classic' in the game track list with a 'strict' checkbox.
    - 'any, prefer retail' is now just 'retail' and 'not strict'
    - 'any, prefer classic' is now just 'classic' and 'not strict'
    - see [README](https://github.com/ogri-la/strongbox#classic-and-retail-addon-support) for priorities
* the follow widgets and features are now disabled if no addon directory is selected (more to follow):
    - the search tab
    - the game track drop down
    - the addon directory drop down

### Fixed

* a bug in wowinterface catalogue generation. 
    - wowinterface.com changed a label from "Classic" to several other things and it broke classic support detection.
        - I don't know how long this has been happening for.

## 4.0.0 - 2021-04-16

### Added

* an addon detail tab accessible by double clicking on either an installed addon or a search result.
    - addon detail tabs can be closed.
    - shortcut `ctrl-w` also closes an open tab *except* the installed, search and log tabs.
    - has a new option `browse local files` if addon is installed locally.
    - has its own menu of buttons to install/re-install, update, remove, pin and ignore.
    - a 'raw data' widget with the fields used within the application.
    - a 'releases' widget that lists all available releases with an install button.
    ' a 'grouped addons' widget that displays the other addons that came with this addon and a file browser link.
    - a logging widget with per-addon logging and adjustable severity level.
* dedicated log tab.
    - will display `(warnings)` or `(errors)` if any warnings or errors present in log.
* `View` menu has a menu option for open addon detail tabs with an additional `close all` button.
* a column on the `installed` tab that shows the status of an addon.
    - a happy green tick if everything is ok.
    - an unhappy red cross if the addon has errors.
    - a stern yellow bar if the addon has warnings.
    - an arrow if an update is available.
    - single-clicking (rather than double-clicking) in this column takes you to the addon detail pane.

### Changed

* console logging is now colour coded.
* console log line now excludes a number of fields including the machine's 'hostname'.
* many error messages have been truncated or made less verbose now they have that context built-in.
* switched from Travis CI to Circle CI.
    - I can't package an `AppImage` from Circle CI however, so this is happening manually for now.
* search pane, styling of the 'already installed' addons now shares the same styling as ignored addons.

### Fixed

* looks like the the gap between the `File` menu labels and their submenus is now gone with an update to JavaFX 15.
    - this only affected some window managers (like mine).
* truncated 'source' column in the search results when in dark mode.
* fixed missing `strongbox.version` value from debug output when run as a binary.

### Removed

* original Swing GUI
* the split window with the tab pane in one half and the notice logger in the other.

## 3.3.1 - 2021-04-03

### Fixed

* added a fix for addons supporting Classic (BC) appearing in the retail game track.
    - affects only curseforge addons
* removed debug statement left in the 3.3.0 release
    - https://github.com/ogri-la/strongbox/issues/240

## 3.3.0 - 2021-02-19

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

## 3.2.1 - 2021-01-31

### Fixed

* fixed issue where URLs of redirect responses were being normalised and the normalised URL didn't exist, causing a 404.
    - https://github.com/dakrone/clj-http/issues/582
    - thanks to @rymndhng of dakrone/clj-http for all the help

## 3.2.0 - 2020-12-30

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

## 3.1.1 - 2020-12-02

### Fixed

* tukui, changes to accommodate their change in API

## 3.1.0 - 2020-11-28

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

* another type of code linting, provided by Joker.
    - see: https://joker-lang.org/
* a `--debug` flag that will run strongbox with lots of output and write a log file.
    - the name of the log file is shown after the application has exited
* ticket templates with instructions on using the new `--debug` flag

### Changed

* 'wowman' was renamed 'strongbox' in The List
    - see: https://ogri-la.github.io/wow-addon-managers/
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

## 0.12.3 - 2020-02-23

### Fixed

* fixes a bug where catalog entries whose 'description' is greater than 255 characters causes a crash while loading the database
    - thanks to https://github.com/rainecheck for reporting this bug

## 0.12.2 - 2020-02-08

### Fixed

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

## 0.6.0 - 2019-05-08

### Added

* Arch Linux PKGBUILD (AUR)
    - see: https://github.com/ogri-la/wowman-pkgbuild
    - see: https://aur.archlinux.org/packages/wowman/
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

## [Unreleased]

### Added

### Changed

### Fixed

### Removed
