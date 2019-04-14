# todo.md

this is my own scratchpad for keeping track of things. it gets truncated frequently.

see CHANGELOG.md for a more formal list of changes by release

## done bucket



## 0.4.0 release

### done

* do not cache bad downloads
    - do not keep the zip file
    - do not keep an etag, although orphaned etags *are* handled
* handle installing bad zip files
    - I just attempted to install an addon called Narcissus and the download failed/timed out
        - unzipping it caused a java.lang.IllegalArgumentException: MALFORMED
        - the bad zip file was preserved in cache
    - done, although etag left for orphan checker
* fixed bug where curseforge.json wasn't updated when etag changed
* clear cached files option
    - clears all cache files
    - done
* clear zip files options
    - clears all downloaded zip files
    - done
* 'clear all' option
    - general housecleaning option
    - done
* clear hidden files
    - .wowman.json
        - done
    - wowmatrix .dat files?
        - done
* fixed bug in gui where empty date field caused peculiar rendering artifacts
    - empty date fields not present when all addons are managed!
    - I need to test against unmanaged installations more often
* fixed bug where slugified 'altname' from catalog (used for matching against installed addons) caused a 404 when uri reconstructed
    - no need to reconstruct uri
    - points to a larger problem where catalog :name is otherwise lost when merged with toc contents
* bug on unmanaged addons dir, re-installing unmanaged addon fails with stacktrace
    - done
* bug, on unmanaged addons dir, installing MogIt doesn't add nfo files to sub-addons
    - try deleting the zip files ...
        - nope, in fact, no downloaded zip file present at all...
    - the addon is showing up as 'matched' in the search panel, possibly an old state hanging around
        - I mistook the highlighted row for being 'selected' and was clicking 'install selected'
        - 'install selected' would merrily install nothing (nothing selected), switch back to installed pane and refresh things with no obvious difference
            - todo: change highlighted colour to not-blue
                - changed to khaki
            - todo: disable 'install selected' until there is something selected
                - done
* bug, curseforge.json doesn't appear to get re-downloaded
    - done
* cached files policy
    - nothing older than a week?
        - why? it's not like we want to go back and scrutinize it
    - nothing older than today?
    - done
* gui, min-widths for updated, installed, available, update? and version fields
    - rest can be elastic
    - done
        - a min-width has been set with a few exceptions
        - a max-width and preferred width have been set for some columns
        - preferred width doesn't seem to have much effect
* gui, optional fields in the search and installed panes
    - prune back the columns if we can
    - columns can be hidden using jxtable preferred method
    - done

### todo

* only download curseforge.json once a day
    - download it to the cache dir so it gets auto-pruned

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
