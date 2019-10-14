# todo.md

this is my own scratchpad for keeping track of things. it gets truncated frequently.

see CHANGELOG.md for a more formal list of changes by release

## 0.11.0 release

### done

### todo

* github as addon host
    - https://github.com/search?q=wow+addon&type=Repositories
* gitlab as addon host
    - https://gitlab.com/search?search=wow+addon
* add TUKUI addon host
    - TUKUI appears to be both addon manager and addon host
    - ELVUI is their flagship addon
    - they have json that can be scraped
        - https://www.tukui.org/api.php

* allow user to specify their own catalogs
    - a url to a catalog that is downloaded and included while loading up the db
* allow user to accumulate addons in a 'user' catalogue
    - the addon may exist on github or similar where no catalogue is maintained

* mac support
    - must be included in CI
* windows support
    - must be included in CI
* investigate state of java packaging
    - https://www.infoq.com/news/2019/03/jep-343-jpackage/
* add a sha256 sum to release file
    - will prevent me from having to download release to generate a sumfile

* it's possible for `.part` files to exist and not be cleaned up
* add 'source' properties to curseforge and wowinterface catalogs
* remove the 'curse-crap-redirect-strategy' in http.clj
    - was used when curse *website* (not api) would redirect us to an unencoded path
* bug, if an addon directory goes missing between restarts, user configuration is lost
    - initially it's ignored, but then the new settings are saved over the top
* bug, we have addons in multiple identical categories. fix this in catalog.clj
    - see 319346
    - remove call to set in db-load-catalog
    - I suspect curseforge
* investigate usage of spec-tools/coerce and remove if unnecessary
* bug, export addon list isn't using selected directory
* when adding an addon-dir, if path ends with /_classic_/Interface/Addons, set game track to classic

## todo bucket

* investigate better popularity metric than 'downloads'
    - if we make an effort to scrape everyday, we can generate this popularity graph ourselves
* gui 'wow' column is inconsistent
    - for curseforge, it's pulling it's value from :gameVersion, which may be empty
        - in which case it pulls it's value from the toc file, which may be different from the selected game track
    - since this is the 'installed addons pane', should the value reflect the value of the installed addon?
        - (and not the value of the addon to be installed)
        - and would this be inconsistent with the other fields that are also changing with new catalog information?
* have the info box scroll the other direction
    - this is possible, see the seesaw examples
* add checksum checks after downloading
    - curseforge have an md5 that can be used
        - unfortunately no checksum in api results
        - they do have a 'fileLength' and a 'fingerprint'
            - fingerprint is 9 digits and all decimal, so not a hex digest
    - wowinterface checksum is hidden behind a javascript tabber but still available
        - wowinterface do have a md5sum in results! score
* database, compare current speed and code against loading addon category data serially
    - as opposed to in three blocks (categories, addons, category-addons). We might save some time and code
* database, investigate prepared statements when inserting for improved speed
* wowman-data, stop publishing a 'daily' release
    - we have multiple catalogs now
    - 0.10.0 uses the raw catalog files directly
    - 0.9.2 was still using the daily release
    - remove the 'daily' release after 0.11.0 is released
* remove debugging? mode
* export to markdown
    - I think I'd like a simple list like:
        * [addon name](https://source/path/to/addon)
    - of course, this would be a different type of export than the one used for import
        - although ... I could possibly parse the list ... and nah.
* add custom highlighting colours 
    - I don't mind my colours but not everybody may
* add a 'tabula rasa' option that wipes *everything* 
    - cache, catalog, config, downloaded zip files
* coloured warnings/errors on console output
    - when running with :debug on the wall of text is difficult to read
    - I'm thinking about switching away from timbre to something more traditional
        - he's not addressing tickets
        - it may have been simpler to use in 3.x.x but in 4.x.x it's gotten a bit archaic
        - I can't drop hostname without leaving pretty-printed stacktraces behind
* catalog, normalise catagories between addons that overlap
    - perhaps expand them into 'tags'? 
    - a lot of these categories are composite
        - break each composite one down into a singular, normalise, have a unique set of tags
* investigate `.csv` as a human-readable but more compact representation
    - might be able to save a MB on extraneous syntax
    - might be able to speed up parsing and loading
    - might be able to drop the two json libraries in favour of just one extra lib
    - depends on profile task
* cache, make caching opt-out and remove all those ugly binding calls
    - bind the value at core app start
    - this may not be possible. 
        - binding happens at the thread level
        - if we start doing download concurrently, we need to pass our binds to the threads
            - which I'm not sure if is possible
        - moving back into bucket until I get around to doing parallel downloads
* testing, capture metrics with an eye to improving performance and speed
    - we have coverage metrics now
    - would like some timing around certain operations
        - like loading the catalog
            - done
        - like downloading and installing the top-10, top-20, top-N addons
            - this could be a good benchmark actually
                - how quickly can one go from 'nothing installed' to '20 addons installed' ?
                - could be tied in with backups/exports
                    - got to have backups+imports happening first
        - identify slow things and measure their improvement
* automatically exclude 'ancient' addons from search results
    - these are addons that haven't been updated in ~18 months
        - wowinterface has a lot of them
* group search results?
    - group by downloads/age/category?
        - it would finally be the best use for category data
* memory usage
    - we're big and fat :(
    - lets explore some ways to measure and then reduce memory usage
    - measuring:
        - https://github.com/clojure-goes-fast/clj-memory-meter
* move away from this merging toc/addon/expanded addon data strategy
    - it's confusing to debug!
    - namespaced keys might be a good alternative:
        - :toc/label and :catalog/label
        - :toc/version and :catalog/version
        - with derived/synthetic attributes having no ns
            - :group-id, :group-count
        - how to pick preferred attributes without continuous (or key else other-key) ?
            - (getattr addon :label) ;; does multiple lookups ...? seems kinda meh
    - post 1.0
    - wait until spec2 is released for an overhaul of current specs
* toggleable highlighers as a menuitem
    - highlight unmatched
    - highlight updates
    - touch of colour against each menuitem would serve as a legend
* toggleable columns as a menuitem
    - they're available from the column menu, but it's a little hidden and contains other fairly useless options like 'horizontal scroll'
* download progress bar *inside* the grid ...?
    - pure fantasy?
* nightly unstable builds
    - building the 'develop' branch once a day
        - making it available as the 'unstable' release that always gets replaced
    - project.clj "x.y.z-unreleased" would be changed to "x.y.z-unstable"
    - development would happen mainly in feature branches
* internationalisation?
    - Akitools has no english description but it does have a "Notes-zhCN" in the toc file that could be used
    - wowman was mentioned on a french forum the other day ..
* a 'stop' button to stop updates would be nice ...
    - closing gui would stop ongoing updates
* bug, changing sort order during refresh doesn't reflect which addon is being updated
    - I think changing column ordering and moving columns should be disabled while updates happen
        - just freeze or disable them or something.
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
* gui, scroll tabs with mouse
* gui, search, order by date only orders the *current page* of results

## wontfix

* addon 'detail' tab
    - link to curseforge
    - donation url
    - other addons by author ?
    - list the hidden/sub dependencies
    - too vague, too open ended, too much effort
* addons distributed as .rar files
    - !BeautyLoot on wowinterface is an example of this
        - https://www.wowinterface.com/downloads/info20212
    - rar is a proprietary format
    - the vast majority of addons use .zip
    - no native support in java/clojure for it
        - library here: https://github.com/junrar/junrar
            - just found it while going through minion source
    - would I consider .tar.gz distributed addons?
        - mmmmmmmm I want to say yes, but 'no', for now.
* fallback to using :group-id (a uri) if curseforge.json is not available
    - low priority
    - curseforge.json will only ever be missing:
        - fresh install and
        - your network connection goes down, or
        - you're a victim of github's 99.999 uptime rating
    - wontfix because:
        - group-id is 'group-id' and *not* 'uri'
            - it may change in the future
        - there is a really really slim chance of this actually happening
            - I don't think it justifies the extra complexity tbh
* curseforge, addons whose :name changes
    - see 'speedyloot' that changed to 'speedyautoloot'
        - they share the same creation date
        - /speedyloot now redirects to /speedyautoloot, so curseforge definitely have support for name changing
        - wowinterface probably doesn't
            - or it does, users are creating duplicate addons
    - ignoring until this becomes a problem somehow
* gui, feature, install addon from local zipfile
    - *not* the 'reinstallation' feature, but literally selecting a zipfile from somewhere and installing it
    - would be good for installing older versions of an addon?
    - would be good for installing addons from unsupported sources
        - wouldn't be able to update it however :(
        - I think I'll stick with supporting sources of addons 
            - rather than enabling ad-hoc installation of unsupported addons
    - see item in TODO for custom user catalogs
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
