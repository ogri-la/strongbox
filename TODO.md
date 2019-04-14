# todo.md

this is my own scratchpad for keeping track of things. it gets truncated frequently.

see CHANGELOG.md for a more formal list of changes by release

## 0.5.0 release

### done

### todo


## todo bucket

* toggleable highlighers as a menuitem
    - highlight unmatched
    - highlight updates
    - touch of colour against each menuitem would serve as a legend
* highlight unmatched addons
* 're-install all' should handle cases where .wowman.json is missing
    - it *is*, but some addons are not being handled (no match, presumably)
        - the undermine journal (match)
        - bartender4 (match)
        - Healbot (no match)
        - DBM (no match)
        - champion commander (no match)
    - some of these are common cases and we should have a static lookup for them
        - healbot = healbot-continued
        - dbm = deadly-boss-mods
    - bartender and undermine journal not matching smell like bugs
* download progress bar *inside* the grid ...?
    - pure fantasy?
* nightly unstable builds
* support for wowinterface.com
    - turns out curseforge isn't the sole addon host around (good!)
    - see AbyssUI
        - it disappeared from curseforge but showed up on wowinterface
    - I suspect Tukui is similar as well, a self-host for addons 
* gui, a 'go' link
    - takes you to the curseforge site
* highlight errors and warnings in notice logger
* internationalisation? 
    - Akitools has no english description but it does have a "Notes-zhCN" in the toc file that could be used
* add an 'about' top level menu
    - it checks if a new version of wowman available
    - link where to find it
    - licence
* a 'stop' button to stop updates would be nice ...
* bug, changing sort order during refresh doesn't accurate reflect what is being updated
* move to XDG preferred data/config directories
    - https://specifications.freedesktop.org/basedir-spec/basedir-spec-latest.html
    - perhaps ignore data directory for now and just use config (~/.config/wowman/)
    - make this customisable?
* arch linux AUR package
* download addon details in parallel
    - speed benefits, mostly
    - share a pool of connections between threads
        - N connections serving M threads
* gui, search pane, indicate results are paginated
* cli, update specific addon
* cli, install specific addon
* cli, colours!
* gui, both panes, filter by categories
* gui, pagination controls in search pane
* gui, feature, install addon from local zipfile
    - *not* the 'reinstallation' feature, but literally selecting a zipfile from somewhere and installing it
    - would be good for installing older versions of an addon?
* gui, scroll tabs with mouse
* gui, search, order by date only orders the *current page* of results
* capture 'total downloads' from curseforge
* addon 'detail' tab
    - link to curseforge
    - donation url
    - other addons by author ?
    - list the hidden/sub dependencies
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
* gui, search pane, clear search button
    - I don't think this is necessary anymore
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
