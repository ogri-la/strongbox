# todo.md

this is my own scratchpad for keeping track of things. it gets truncated frequently.

see CHANGELOG.md for a more formal list of changes by release

## 0.11.0 release

### done

* github as addon host
    - ref
        - https://github.com/ogri-la/wowman/issues/68
        - https://github.com/search?q=wow+addon&type=Repositories
    - github source type supported in catalogues and gui
        - done
    - github addons can be installed
        - github-api/expand-summary added
            - done
    - curseforge .pkgmeta parsed and the correct directory name used
        - https://github.com/oUF-wow/oUF/wiki/Embedding#packagers
        - oUF .zip downloads have a directory name with the version in it
            - "oUF-1.8.1"
            - which will fuck us
            - adibags does this as well. I suspect most are doing this
        - https://authors.curseforge.com/knowledge-base/projects/3451-automatic-packaging#release-types
        - urgh. no. stop. wait
            - ok, after a short look at 10 github hosted addons there is a lot of variety
                - too much
            - only addons that fit a very specific criteria will be installable:

---

for an addon on github to be installable by wowman, it must:

1. be using 'releases'
2. have at least one ready-to-go asset attached to the release
3. if the word 'classic' is present in the asset name, it's assumed to belong to the classic game track
4. if there are multiple assets in a release and no way to distinguish between, 'retail' is assumed and the first retail asset will be used
5. if a release is determined to be uninstallable, the next release will attempted 

(do we do this with wowi and curse? check ...)
6. repeat until all releases in the first page of github results have been attempted

---

        - done

    - refresh search results
        - new github addon is not present for some reason
        - can't replicate
        - done
* allow user to accumulate addons in a 'user' catalogue
    - why? the addon may exist on github or similar where no catalogue is maintained
    - duplicate addons and categories are handled without fuss in the db
        - done
    - addon is automatically installed when 'added' through the gui
        - done
* add 'source' properties to curseforge and wowinterface catalogs
    - done
* bug, export addon list isn't using selected directory
    - done
* github, look for a .toc file to better determine classic or not
    - fall back to release name scraping only if a .toc file not found
        - not finding a toc file may itself be an indication of problems...

---

how to determine game tracks
* if root level .toc file found
* download
* look for interface version in comments
    - regular toc file parsing won't work here
    - `## Interface: 80205`
* look for interface version in commented-comments:
    - `# ## Interface: 11302`
    - `# # Interface: ...`
* set game tracks from what we find

if single asset and no game-track present
- look for 'classic' in asset name, default to retail

if single asset and single game-track present
- use that game track

if single asset and multiple game tracks present
- assume asset is compatible with both game tracks

if multiple assets present and no game track present
- check each asset for mention of 'classic'

if multiple assets present and single game track present
- do we treat the game tracks in the catalogue (that may be old) as 'hints' rather than law?
    - in which case, we need to check each asset for mention of 'classic'
    - the alternative is assume all assets are of the same game track type, which doesn't seem right

if multiple assets present and multiple game tracks present
- we still need to differentiate which asset belongs to which game track
    - if we're unable to differentiate then a warning should be issued
        - we know the addon supports multiple game tracks but it's not clear from the assets which is which

so, 'peeking' at the `.toc` file will only be helpful in cases where there is a *single* asset. 
multiple assets will always require differentiation, but we can tune warnings/error messages based on what we know from the toc file

---

    - done

* remove the 'curse-crap-redirect-strategy' in http.clj
    - was used when curse *website* (not api) would redirect us to an unencoded path
    - done
* allow user to accumulate addons in a 'user' catalogue
    - how is this catalogue updated?
        - it will contain information that will remain static after initially created
        - typically wowman downloads the updated catalogue from remote
            - that won't happen here
        - I think we could get away with updating this catalogue once a week?
            - inspect 'last-updated' in user-catalogue
        - went with a manual update for now
    - done
* introducing a new source (tukui) to the catalog will break older wowman clients
    - the database is quite strict and will refuse/error on unfamiliar data
    - done in 0.10.2
* bug, handle 404/whatever errors when switching to a catalog that doesn't exist remotely
    - like all brand new catalogs and github unavailability
    - done in 0.10.2
* add TUKUI addon host
    - TUKUI appears to be both addon manager and addon host
    - ELVUI is their flagship addon
    - they have json that can be scraped
        - https://www.tukui.org/api.php
    - done
* add a sha256 sum to release file
    - will prevent me from having to download release to generate a sumfile
    - done
* bug, we have addons in multiple identical categories. fix this in catalog.clj
    - remove call to set in db-load-catalog
    - I suspect curseforge
        - definitely just affecting curseforge
        - it was even present in the tests
    - done
* it's possible for `.part` files to exist and not be cleaned up
    - done

### todo

* github bug, non-addon git repo fails to install
    - https://github.com/koekeishiya/yabai
    - make this a softer failure
        - "does not look like an addon"

* mac support
    - must be included in CI
* windows support
    - must be included in CI
* bug, if an addon directory goes missing between restarts, user configuration is lost
    - initially it's ignored, but then the new settings are saved over the top
* investigate usage of spec-tools/coerce and remove if unnecessary
* when adding an addon-dir, if path ends with /_classic_/Interface/Addons, set game track to classic

## todo bucket (no particular order)

* allow user to specify their own catalogs
    - a url to a catalog that is downloaded and included while loading up the db
    - different from the 'user catalog'
* rename 'go' column to 'catalogue' 
* gitlab as addon host
    - https://gitlab.com/search?search=wow+addon
    - returned to bucket 2019-12-04, notes:
        - gitlab doesn't handle releases like github does
            - https://stackoverflow.com/questions/29520905/how-to-create-releases-in-gitlab
        - there are very few gitlab addons (88)
        - api is quite slow
    - if somebody asks for this especially I'll consider it
* add support for user supplied github token
    - necessary if they want a large number of github addons without hassles
* when curseforge api is down users get a wall of red error messages with very little useful information
    - see issue 91: https://github.com/ogri-la/wowman/issues/91
        - the error message has been improved but we still get a red wall of text
        - aggregate error messages?
* investigate state of java packaging
    - https://www.infoq.com/news/2019/03/jep-343-jpackage/
* github-api, also look for 'retail' in addon name to determine game track
    - rather than just 'classic'
* add support for reconciling addons by 'x-curse' and 'x-wowi' ids
    - example: https://github.com/ascott18/TellMeWhen/blob/master/TellMeWhen.toc#L19-L20
* add an option that forces installation of addon if matching game track not found
    - enable it by default
    - add a warning when installing an addon that doesn't match game track
    - add a summary after each refresh about the state of installed addons
        - "123 addons installed, 1 unmatched addon, 2 retail addons installed"
        - "123 addons installed, 2 classic addons installed"
    - preserve this in user settings
    - perhaps couple this with the GUI logic for the status bar down the bottom
* bug, clearing catalogues and clicking refresh doesn't see the database rebuilt
    - the catalog is downloaded though
* new tab for dedicated log
* import/export, capture game track of exported addon dir?
* import/export, export user catalogue
* github, importing an exported addon list with a github addon won't see that addon installed
    - unless that addon is present in the user catalogue
        - which in a fresh install where a list of addons are being restored is unlikely...
    - this is interesting actually. the exported addon list has become a mini-catalogue
        - some addons require the larger catalogue to resolve
        - github addons are resolved and installed by a different means...
* github, installation from github via import menu not updating log until finished
    - this is an async issue
* simplify `install-addon` interface in core.clj
    - we need to provide an installation directory which can be pulled from the application state
* rename references of 'uri' to 'url'
* version pinning
    - user can opt to install a specific release of an addon
    - automatic updates for that addon are thereafter blocked
* alpha/beta opt-in
    - user can opt to install alpha/beta/no-lib releases per-addon
* rename 'reinstall all' to 'reconcile'
    - steal from the best
    - make the reconcile automatic
        - if a .wowman.json file isn't found
    - remove the 'first time instructions' from the readme
        - it should just fucking do it
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
* add support for finding addons by url for other hosts
    - wowinterface
    - curseforge
    - but these addons already exist in the main catalog ...
        - should they be taken to a search results page?
        - because what is presumably happening is the user can't find their addon in the search results (or can't be arsed to) and is saying "just install this please"
            - but wowman uses catalogs as a source of data, so if it can't find the addon in the catalog, then what? 
                - fail? but the user just gave us a URL (UNIVERSAL RESOURCE LOCATOR) ! what is the fucking problem here?
    - the problem is expectations. wowman doesn't scrape addon host website HTML if it can avoid it
        - and user enters addon host website URL
    - this should be solved with more sophisticated catalogue searching
        - parse identifiers from URL, like source and source ID, then display search results
            - again, by encouraging the copying+pasting of URLs and then failing to find results when the URL IS RIGHT THERE AND WORKING we set ourselves up for failure and the user for disappointment/frustration
    - parking this

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
