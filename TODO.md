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
* cache downloaded file based on addon AND addon version
    - is this still a thing?
    - no, it's not
* bug, install-addon when addon download fails
    - done
* gui, prompt when attempting to install or delete many addons at once
    - done
* on cache hit, don't display "downloading summary data for ..."
    - it's just noise at this point
    - done
* more graceful handling of github errors when downloading curseforge.json
    - see recent 503 varnish error "bad backend"
    - done
* send etag header to github to prevent downloading unmodified curseforge.json file
    - need to capture the etag sent to us when we first request the file
    - etag should be stored and sent on subsequent requests for file
    - where to store? curseforge.etag ?
    - done
        - .etag files are stored in ./state/cache/filename.ext.etag
        - feels less than ideal but works nicely with github
        - untested with curseforge and addon downloads
* store download-uri in .wowman.json file
    - we'll prefer the uri in the curseforge file but if that is unavailable we have a fallback
    - done
* bug, FileNotFoundException (PermissionDenied) attempting to unzip addon belonging to root
	- nothing reporting in log, just stacktrace in terminal
	- done
* 0.2.0 release
    - merge to master, tag, update readme with new downloadable
    - done

## 0.3.0/unreleased

### done

* fixes the typo in 'downloading addon summary data foraddon'
* gui, shift 'update selected' and 'delete selected' and 're-install selected' into a context menu
    - done
* gui, installed pane, highlight rows that need updating
    - remove/hide the 'update?' column
    - done
* remove uri decoding in utils json handling
    - done, also did a general cleanup of utils
* regression, "re-install all" not selecting addons as it re-installs them
    - done, it now selects as it's installing and selects again as it refreshes.
        - when everything is cached this looks a bit silly
* bug, no toc displayed after installing many into fresh dir
    - it's downloading summary stuff but it needs to be async
    - done, the installation process is now slightly different from the search panel
        - it switches to the installed pane, expands the summary, downloads the addon, re-loads the installed addon list
        - this gives us many small partial updates instead of a freeze and a single massive update
* hid load/save settings from File menu unless in debug mode
    - these were only used in the beginning to kick start development
* bug, utils/download-file and utils/download are using two separate user agents
    - unify these
    - figure out a way to keep the version in the user agent up-to-date
    - done, introduced new dependency 'versioneer'

### todo

* gui, search, highlight rows that are installed
* gui, search box gets focused immediately

## todo bucket

* do not cache bad downloads
    - do not keep the zip file
    - do not keep an etag, although orphaned etags *are* handled
* handle installing bad zip files
    - I just attempted to install an addon called Narcissus and the download failed/timed out
        - unzipping it caused a java.lang.IllegalArgumentException: MALFORMED
        - the bad zip file was preserved in cache
* internationalisation? 
    - Akitools has no english description but it does have a "Notes-zhCN" in the toc file that could be used
* add an 'about' top level menu
    - it checks if a new version of wowman available
    - link where to find it
    - licence
* a 'stop' button to stop updates would be nice ...
* bug, changing sort order during refresh doesn't accurate reflect what is being updated
* gui, hide columns using jxtable preferred method
* move to XDG preferred data/config directories
    - https://specifications.freedesktop.org/basedir-spec/basedir-spec-latest.html
    - perhaps ignore data directory for now and just use config (~/.config/wowman/)
    - make this customisable?
* arch linux AUR package
* clear cached files option
    - clears all cache files
* clear zip files options
    - clears all downloaded zip files
* clear hidden files
    - .wowman.json
    - wowmatrix .dat files?
* cached files policy
    - nothing older than a week?
* download addon details in parallel
    - speed benefits, mostly
    - share a pool of connections between threads
        - N connections serving M threads
* gui, min-widths for updated, installed, available, update? and version fields
    - rest can be elastic
* gui, search pane, indicate results are paginated
* cli, update specific addon
* cli, install specific addon
* cli, colours!
* gui, both panes, filter by categories
* gui, pagination controls in search pane
* gui, feature, install addon from local zipfile
    - *not* the 'reinstallation' feature, but literally selecting a zipfile from somewhere and installing it
* gui, search pane, clear search button
* gui, scroll tabs with mouse
* gui, search, order by date only orders the *current page* of results
* capture 'total downloads' from curseforge
* addon 'detail' tab
    - link to curseforge
    - donation url
    - other addons by author ?
    - list the hidden/sub dependencies
* gui, optional fields in the search and installed panes
    - prune back the columns if we can
* testing, capture metrics with an eye to improving performance and speed
* issue a warning when addons unpack directories that don't share a common prefix
    - this would hopefully alert users that some shitty addons are being sneakily installed, like SlideBar or Stubby
        - we could go one further and filter/prompt the user if they actually want to unpack these directories
* fallback to using :group-id (a uri) if curseforge.json is not available
    - low priority
    - curseforge.json will only ever be missing:
        - fresh install and
        - your network connection goes down, or
        - you're a victim of github's 99.999 uptime rating

## wontfix

* bug, 'whoa thick frames' is reporting a version number of '0.9.zip'
    - toc file just says '0.9'
    - update: it's using the version value from curseforge which *is* '0.9.zip'
        - addon problem, wontfix
    - update2: this seems to happen a lot actually
        - I can also see
            - zep-mix-damage-taken
            - training grounds
            - pvp-mate addon
            - mekka robo helper
            - jcs media sounds
* gui, stateful buttons
    - don't allow enabled 'delete selected' buttons if nothing is selected
    - not going to coddle the user. deleting nothing will see nothing deleted.
* cli, interactive interface when no specific action specified
    - you have N addons installed. what do you want to do? (list, update, update-all, delete) etc
    - this is a huge amount of hassle
