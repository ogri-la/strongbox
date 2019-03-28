# todo.md

this is my own scratchpad for keeping track of things. it gets truncated frequently.

see CHANGELOG.md for a more formal list of changes by release

## done bucket

* travis to generate and upload .jar artifacts
    - only on tagged (x.y.z) releases on the master branch
        - turns out tags have no branch
    - done
* bug, regression, gui, installed addons not refreshing after update
    - fixed
* 0.1.0 release
    - done!
* gui, error after installing addon
    - failed to find value <something> in column '-addon-id'
        - 19-02-28 00:51:20 WARN [wowman.ui.gui:267] - failed to find value tidy-plates in column 'addon-id'
        - 19-02-28 00:51:24 WARN [wowman.ui.gui:267] - failed to find value omni-cc in column 'addon-id'
    - can't replicate any more
        - possibly related to the problems with event threading earlier

## 0.2.0 release

### done

* gui, prompt when attempting to install or delete many addons at once
    - done
* on cache hit, don't display "downloading summary data for ..."
    - it's just noise at this point
    - done
* more graceful handling of github errors when downloading curseforge.json
    - see recent 503 varnish error "bad backend"
    - done

### todo

* send etag header to github to prevent downloading unmodified curseforge.json file
    - need to capture the etag sent to us when we first request the file
    - etag should be stored and sent on subsequent requests for file
    - where to store? curseforge.etag ?
* bug, FileNotFoundException (PermissionDenied) attempting to unzip addon belonging to root
	- nothing reporting in log, just stacktrace in terminal

## todo bucket

* store download-uri in .wowman.json file
    - we'll prefer the uri in the curseforge file but if that is unavailable we have a fallback
* gui, shift 'update selected' and 'delete selected' and 're-install selected' into a context menu
* arch linux AUR package
* download addon details in parallel
    - speed benefits, mostly
    - share a pool of connections between threads
        - N connections serving M threads
* gui, stateful buttons
    - don't allow enabled 'delete selected' buttons if nothing is selected
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
* cache downloaded file based on addon AND addon version
    - is this still a thing?
* cli, update specific addon
* cli, install specific addon
* cli, colours!
* cli, interactive interface when no specific action specified
    - you have N addons installed. what do you want to do? (list, update, update-all, delete) etc
* gui, both panes, filter by categories
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

