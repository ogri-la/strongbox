# todo.md

this is my own scratchpad for keeping track of things. it gets truncated frequently.

see CHANGELOG.md for a more formal list of changes by release

## done

* gui, sizing columns
    - done
* search, install addons from search pane
    - done
* installed, auto-load curseforge addons
    - done
* installed, bug, list not refreshing after installing/updating
    - done
* search, update after install
    - done
* installed, notification that gui is doing something
    - done
* grids, sort contents by column headers
    - done
* gui, 'update all' from File menu
    - done, see 'Addons' menu
* bug, gui, stacktrace selecting an item
    - done, select-item was receiving both a deref'ed and a derefable value for 'ui'
* make the distinction between addon types clearer
    - done
* gui, hide message log until it's working
    - done, logging has been improved
* cli, scrape command
    - done
* cli, gui command
    - done
* gui, changing wow directories auto saves settings
    - done
* gui, update-all needs to go to background thread
    - also needs to update gui as it progresses
    - done
* cli, args override local config
    - done
* cli, list command
    - done
* cli, list-updates command
    - done
* cli, update all addons
    - done
* gui, feature, re-install addon
    - done
* gui, installed pane, hide dependencies
    - done.
    - known non-primary addons are now hidden
* bug, gui, groups of addons where there is no primary will be entirely hidden, like dbm
    - switch :installed-addon-list to groups with group-level metadata?
    - done
* gui, feature, remove/delete addon
    - uninstalling an addon finds all of it's direct dependencies and uninstalls them also
    - done
* gui, truncate updated-date
    - done
* switch from objects and object serialisation in scraper
    - this is preventing easy comparison in tests
* scraper, partial scrapes
    - it's possible to do a full scrape (330+ pages to download) and then supplement it with just those that have changed in the last N days (3-4 pages to download)
    - implement a download process
        - look online for latest 'full' list, there should always be one
        - if it's less that 330+ days old then it should always be a net win to download the latest updates
        - determine gap between list and now
        - download updates
        - update curseforge-updates.json
        - on load, load curseforge, merge curseforge-updates
    - done
* gui, search, 'install' blocks until installed
    - done
* disable writing expanded-addons.json when not debug mode
    - done
* gui, make log line simpler for notice window
    - done
* ensure a writeable directory for cached files et al
    - a 'state' or 'conf' type directory
    - is writable
    - done
* tests, are broken
    - fixed
* make client useragent friendlier
    - let curseforge identify this client
    - include client version, url
    - done
* gui, format interface version
    - done
* run as a jar
    - done!
* bug, changing addon directory doesn't update addons
    - done
* bug, state directory not being created
    - done
* download curseforge.json from github
    - done
* bug, 'update all' doesn't remove the 'true' value in the 'update?' column and requires a refresh
    - done
* set licence
    - agpl?
    - see metabase: https://www.metabase.com/license/
        - done
* bug, failure to find a cfg.json file causes a stacktrace
    - setting a directory causes an auto-save however
        - done
* bug, failure to find a curseforge.json causes a stacktrace
    - related to downloading curseforge.json from github
        - done
* remove 'host' from terminal log line
    - done
* bug, 'failed to match' error message regression
        - 2019-03-09 08:38:39.186 rama WARN [wowman.core:419] - failed to match installed addon 'world-quest-tracker' to an addon online. try searching for and re-installing it from the search menu
    - fixed, bad nesting
* bug, gui, 'refresh' action doesn't show which addons are being updated
    - fixed, refresh button wasn't releasing lock
* bug, bad data is entering the table somehow
    - more often now after fixing selected-items bug
    - suspect race condition somewhere
        - was an event dispatch thread issue with selection of rows happening outside of edt
    - done

## todo, 0.1.0 release

---

! first steps for new users will be: 
    - select a wow directory
    - see addons appear
    - see them updated from curseforge
  this process can't be broken

---

* list of available addons are regenerated weekly and updated daily
    - updates are merged into curseforge.json
        - done
    - datestamp in file should be updated
        - done
    - order should be preserved for easy diffing
        - done

* travis-ci integration

* upload .jar artifacts

* add curseforge scraping to CI

* upload generated/updated curseforge.json to github

* scrutinise all files

* 0.1.0 release

## todo

* send etag header to github to prevent downloading unmodified curseforge.json file
    - need to capture the etag sent to us when we first request the file
    - etag should be stored and sent on subsequent requests for file
    - where to store? curseforge.etag ?
* bug, FileNotFoundException (PermissionDenied) attempting to unzip addon belonging to root
	- nothing reporting in log, just stacktrace in terminal
* gui, min-widths for updated, installed, available, update? and version fields
    - rest can be elastic
* gui, search box gets focused immediately
* clear cached files option
    - clears all cache files
* clear zip files options
    - clears all downloaded zip files
* cached files policy
    - nothing older than a week?
* gui, search pane, indicate results are paginated
* gui, error after installing addon
    - failed to find value <something> in column '-addon-id'
        - 19-02-28 00:51:20 rama WARN [wowman.ui.gui:267] - failed to find value tidy-plates in column 'addon-id'
        - 19-02-28 00:51:24 rama WARN [wowman.ui.gui:267] - failed to find value omni-cc in column 'addon-id'
* cache downloaded file based on addon AND addon version
    - is this still a thing?
* cli, update specific addon
* cli, install specific addon
* cli, colours! 
* cli, interactive interface when no specific action specified
    - you have N addons installed. what do you want to do? (list, update, update-all, delete) etc

* gui, both panes, filter by categories
* gui, prompt when attempting to install many addons at once
    - skip prompt on re-installation
* gui, pagination controls in search pane
* gui, feature, install addon from local zipfile
    - *not* the 'reinstallation' feature, but literally selecting a zipfile from somewhere and installing it
* gui, search pane, clear search button
* gui, installed pane, highlight rows that need updating
* gui, search, highlight rows that are installed
* gui, scroll tabs with mouse
* gui, search, order by date only orders the *current page* of results
* capture 'total downloads' from curseforge
* addon 'detail' tab
    - link to curseforge
    - donation url
    - other addons by author ?
    - list the hidden/sub dependencies
* gui, optional fields in the search and installed panes

## elaboration required

* testing, capture metrics with an eye to improving performance and speed
* mount, reconsider the use of this library
    - might make gui wrangling easier
* headless gui mode?
    - ./wowman -a update-all # should: load the gui, call update-all, exit


