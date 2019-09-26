# todo.md

this is my own scratchpad for keeping track of things. it gets truncated frequently.

see CHANGELOG.md for a more formal list of changes by release

## 0.10.0 release

### done

* investigate switching to an embedded database
    - there is a lot of catalog-wrangling code happening and it's getting obscure
        - there is new code now but it's less obscure
    - searching for addons is really limited
        - especially now that we have new dimensions
    - db creation could happen in place of catalog generation
        - or the database could be created from the catalog
            - if db is smaller than catalog, this might be a consideration
        - I think generating the database from the plaintext/json catalog is the most open and flexible
            - open in that plain text/json is the easiest to inspect/parse/reuse
            - flexible in that generating an in-memory database on each load avoids database migrations
                - still need to be careful with changes going forward, but I have been with the catalog so far
        - done
    - update tests so we get a fresh db
        - follow per-case/per-test fixture rules
        - done
    - replace :addon-summary-list usage internally with database
        - done
    - replace :installed-addon-list usage internally with database
        - we'll need some way of triggering changes
            - I've done this by updating the state with some stats 
        - need to think a bit more about this one now that :addon-summary-list is happening
            - should this replace .wowman.json files?
                - no, because database isn't permanent
            - what benefits are there to storing the list of installed addons in the database rather than in an array?
                - we've already discovered it can be painful to re-create arrays and maps
* user-agent needs to be updated
    - it using a naive (subs ...) call
    - done
        - should handle anything I throw it from now on
* short catalog, full catalog
    - the catalog is getting big now and will only get larger
        - curseforge and wowinterface keep accumulating new addons
        - other sources will come along
        - database loading operation is already taking a little too long for my liking
    - a lot of addons could be removed as simply being 'too old'
        - addons that haven't been updated for two or three releases (6 years) for example
    - I want to preserve the entirety of the catalog if possible though
        - perhaps a game setting to opt-in to the larger download
        - done
    - investigate how small we can reasonably get the catalog
        - after removing addons not updated since before beginning of last expac (Legion):
            - 6555 addons total
            - 3.1MB file
            - 1.5 second db load time
                "Elapsed time: 1.222319 msecs" (categories)
                "Elapsed time: 483.493881 msecs" (addons)
                "Elapsed time: 860.562001 msecs" (category->addons)
        - there are other tricks I could use to cut out some of the fields and just generate them at load time
            - I think the structure of the catalog will need a more thoughtful revision though
        - done
    - support N catalogs
        - full, short, curseforge, wowinterface
            - done
        - curseforge and wowinterface are proper catalogs
            - they're missing 'source'
                - hacked around for now
            - done
        - done
* remove 'updating' catalogs
    - a full weekly scrape is good enough
    - this logic has introduced a *lot* of code that can be removed
    - scraping curseforge api doesn't seem too onerous anymore
    - done
* remove html scraping of catalogs
    - wowinterface will have some exceptions
    - done
* 'scrape' and 'update' are not great terms
    - scrape means 'complete update'
    - update means 'partial update'
    - I may be removing the updating of catalogs in favour of full scrapes
    - done
        - only 'scrape' remains now
        - 'update' reserved soley for 'updating addons' now

### todo

* download-catalog bug
    - I *think* something or things are trying to read the catalog before it has finished downloading
        - this is causing malformed json errors
    - download the catalog to a temporary name and then move into place
* investigate switching to an embedded database
    - compare current speed and code against loading addon category data serially
        - as opposed to in three blocks (categories, addons, category-addons). We might save some time and code
    - investigate prepared statements when inserting
* bug, 'clear cache' didn't delete the catalog.json
* gui tests are bypassing the path wrangling because the envvar library is using thread-local `binding`
    - change path access to an atom
    - I *think* this may have something to do with a truncated catalog I've encountered now (twice)
* add checksum checks after downloading
    - curseforge have an md5 that can be used
        - unfortunately no checksum in api results
        - they do have a 'fileLength' and a 'fingerprint'
            - fingerprint is 9 digits and all decimal, so not a hex digest
    - wowinterface checksum is hidden behind a javascript tabber but still available
        - wowinterface do have a md5sum in results! score
* bug, curseforge.json is getting a strange duplication of results while generating the catalog
    - this is preventing automated catalog *updates*, not the full regeneration apparently
    - I can't replicate this anymore. It may show up later, but for now it's blocking a 0.8.0 release
    - also, catalog generation is now done via the api

## todo bucket

* bug, we have addons in multiple identical categories. fix this in catalog.clj
    - see 319346
    - remove call to set in db-load-catalog
* investigate usage of spec-tools/coerce and remove if necessary
* wowman-data, stop publishing a 'daily' release
    - we have multiple catalogs now
* remove debugging? mode
* bug, export addon list isn't using selected directory
* export to markdown
    - I think I'd like a simple list like:
        * [addon name](https://source/path/to/addon)
    - of course, this would be a different type of export than the one used for import
        - although ... I could possibly parse the list ... and nah.
* switch catalog loading to load-json-file-safely
* when adding an addon-dir, if path ends with /_classic_/Interface/Addons, set game track to classic
* add a sha256 sum to release file
    - will prevent me from having to download release to generate a sumfile
* add custom highlighting colours 
    - I don't mind my colours but not everybody may
* add ElvUI support. they have json that can be scraped
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
* allow user to specify their own catalog
    - what can we do to support adhoc lists of addons from unsupported hosts?
    - we have both curseforge and wowinterface supported now
* testing, capture metrics with an eye to improving performance and speed
    - we have coverage metrics now
    - would like some timing around certain operations
        - like loading the catalog
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
* windows support
    - eh. I figure I can do it with a VM. I just don't really wanna.
    - for the 1.0.0 release
    - I now have an old WinXP (64bit!) machine
        - one of the first 64bit Athlons available
        - this should force some performance optimisations as well
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
* addon 'detail' tab
    - link to curseforge
    - donation url
    - other addons by author ?
    - list the hidden/sub dependencies

## wontfix

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
