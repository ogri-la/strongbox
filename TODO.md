# todo.md

this is my own scratchpad for keeping track of things. it gets truncated frequently.

see CHANGELOG.md for a more formal list of changes by release

## 0.9.0 release

### done

* investigate switching from scraping to api
    - maintain the scraping interface as well? 
        - am I worried the api will go away??
        - added a separate todo for removing html scraping interface
    - curseforge 
        - spec here: https://twitchappapi.docs.apiary.io/
        - example here: https://addons-ecs.forgesvc.net/api/v2/addon/3358/files
        - switching to api-scraped addons I've gone from 6998 to 6763 (253 difference)
            - can't see any pattern between them
            - after closer inspection I still can't see anything
            - I can't leave any addon behind, especially not for mysterious reasons
                - I'm going to generate the catalog the old fashioned way but add the project id
                - project id can be used with api
                    - missing addons are available directly
            - layday (of instawow) has been good enough to do some investigation of the missing addons
                - they are either: marked as experimental, unavailable or have no files that can be downloaded
                - one missing addon popped up overnight in a fresh scrape which was interesting
                    - it's last modified date was last month though
        - add project id to catalog
            - done
        - use api to expand addon summaries
            - done
    - wowinterface
        - investigate
            - https://github.com/layday/instawow/blob/master/instawow/resolvers.py#L158-L160
        - catalog generation
            - api not suitable for catalog generation
                - it's cool I can download all the details in just one call, but there is no category information
                - it's already missing a description and a created date as well
            - done
        - addon expansion
            - there is actually very little data in the /filedetails endpoint
            - there is more addon data in the full catalog download that the details endpoint
                - perhaps combine the two?
                    - do a web scrape and moosh it with the API catalog?
            - done
* add :source-id and :source to .wowman.json file
    - done
* use :source-id as preferred way to match installed addons to the catalog
    - preserve backwards compatibility for older installations missing :source-id
    - preserve extended matching (on :name, etc) for addons not yet matched to catalog
    - done
        - I just added another dimension to the matching, nothing else was changed

### todo

* classic addons handling
    - curseforge have addons bundling classic versions in with regular versions
        - the api distinguishes them with a 'game_flavour' field
    - change :interface-verson to a list?
        - wowinterface supports this with it's "UICompatibility" 
        - curseforge has "gameVersion" and "sortableGameVersion" 
            - but these look like they're handling the most recent release (which may or may not be classic)
            - better yet, CF has "gameVersionLatestFiles" with a "gameVersion" and "gameVersionFlavor"
                - also has "fileType" which indicates alpha (3)/beta (2)/stable (1) type releases
                - can't count on 'classic' and 'retail' ever being the only two.
                    - the interface version and game flavour should be combined
                        - (classic, 1.13.2)
                        - (retail, 8.2.0)
                        - (classic-bc, 2.?.?)
                    - https://us.forums.blizzard.com/en/wow/t/will-classic-have-the-expansions-added/133699/19
* add checksum checks after downloading
    - curseforge have an md5 that can be used
    - wowinterface checksum is hidden behind a javascript tabber but still available
* can a list of subscribers be setup in github to announce releases?
* coloured warnings/errors on console output
    - when running with :debug on the wall of text is difficult to read
* moves raynes.fs to clj-commons/fs
* 'default' action in the cli should exit with retcode other than 0
    - it means an action wasn't specified or an unsupported action was specified
        - main/validate would prevent casual bypassing of this

## todo bucket

* remove 'updating' catalogs
    - a full weekly scrape is good enough
    - this logic has introduced a *lot* of code that can be removed
    - scraping curseforge api doesn't seem too onerous anymore
* remove html scraping of catalogs
    - pending investigation of wowinterface
* bug, 'clear cache' didn't delete the catalog.json
* bug, I don't see deadly-boss-mods-classic in wowi catalog
    - it should have definitely made it into the last scrape
* bug, curseforge.json is getting a strange duplication of results while generating the catalog
    - this is preventing automated catalog *updates*, not the full regeneration apparently
    - I can't replicate this anymore. It may show up later, but for now it's blocking a 0.8.0 release
* ensure test coverage doesn't drop below threshold
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
* support for multiple addon directories
    - well, supporting for remembering and quickly switching between addon dirs
* 'scrape' and 'update' are not great terms
    - scrape means 'complete update'
    - update means 'partial update'
* curseforge scraping is very quiet when everything is cached
    - unlike wowinterface
* rename 'install-dir' config to 'addon-dir' perhaps?
    - 'install-dir' is ambiguous, it could be talking about installation dir of wowman
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
* toggleable highlighers as a menuitem
    - highlight unmatched
    - highlight updates
    - touch of colour against each menuitem would serve as a legend
* toggleable columns as a menuitem
    - they're available from the column menu, but it's a little hidden and contains other fairly useless options like 'horizontal scroll'
* download progress bar *inside* the grid ...?
    - pure fantasy?
* nightly unstable builds
* internationalisation? 
    - Akitools has no english description but it does have a "Notes-zhCN" in the toc file that could be used
* a 'stop' button to stop updates would be nice ...
    - closing gui would stop ongoing updates
* bug, changing sort order during refresh doesn't reflect which addon is being updated
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
