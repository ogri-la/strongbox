# todo.md

this is my own scratchpad for keeping track of things. it gets truncated frequently.

see CHANGELOG.md for a more formal list of changes by release

## 0.8.0 release

### done

* download release information in background after every has been init'ed
    - done
* delete 'curseforge.json' if present in data-dir
    - done
* consolidate date/time wrangling logic around one library, please
    - done
* updates to catalog via travis
    - script to scrape catalog
        - done
        - see wowman-data/update.sh
    - sources have their latest updates scraped daily
        - done
    - sources are completely scraped weekly
        - done ... to be confirmed
    - can Travis commit to the same repository that it's testing?
        - won't that mess with triggers?
            - not if you turn them off! but also if you leave them on! lets not mess with infinite testing loops
        - it can!
    - catalog.json becomes a build artifact and a 'release'
        - but we replace the release daily rather than accumulate them
        - done!
* fixed bug in updating curseforge where old/bad paths to files caused a NPE
* fixed bug in updating curseforge and wowinterface where data directories without the respective catalog would fail spec
* gui, search, deselect selected addons after successful installation
    - done
        - this was *very* minor, can't believe it ever bothered me

### todo

* better handling of shitty addons
    - below addons are known to be mangled/corrupt/shit in some way
        * "99 bottles of beer", wowinterface
            - extracts to Interface/Addons/
        * "!Borders", "!Pager", wowinterface
            - have top-level folder "__MACOSX"
        * "-ractionbuttonstyle-luna"
            - has top-level folder "media"
        * "Desdinova BGArt", wowinterface
            - has no .toc file
    - all of the above can be 'fixed' by looking for a .toc file in the top level directories
        - if *any* top level directory is missing a .toc file, refuse to install addon
    - another potential cause of shittiness is top-level files
        - same logic applies. refuse to install addon if top-level *files* exist

* issue a warning when addons unpack directories that don't share a common prefix
    - this would hopefully alert users that some shitty addons are being sneakily installed, like SlideBar or Stubby
        - we could go one further and filter/prompt the user if they actually want to unpack these directories
* code quality, we're sorely lacking in tests and test coverage metrics
    - I've added cloverage to get some coverage feedback
    - average coverage is 53%
    - raising that to 60% initially seems like a good goal with 80% or 90% as a stretch
* export+import
    - wowman is strictly an addon manager, not an auxillary WoW manager
        - I won't be backing up screenshots or addon state or anything like that
    - export a simple list of addons that can be re-read (imported) later
    - an idle thought until I saw wowmatrix has it
        - they have a wordpress plugin and a simple text file
    - json, yaml and xml serialisations as a minimum
        - these are the most common and versatile
    - friendly text and html formats
        - who on earth would use such a thing? and is it worth the added complexity?

## todo bucket
* cache, make caching opt-out and remove all those ugly binding calls
    - bind the value at core app start
    - this may not be possible. 
        - binding happens at the thread level
        - if we start doing download concurrently, we need to pass our binds to the threads
            - which I'm not sure if is possible
        - moving back into bucket until I get around to doing parallel downloads
* support for multiple addon directories
* 'scrape' and 'update' are not great terms
    - scrape means 'complete update'
    - update means 'partial update'
* curseforge scraping is very quiet when everything is cached
    - unlike wowinterface
* 'default' action in the cli should exit with retcode other than 0
    - it means an action wasn't specified or an unsupported action was specified
        - main/validate would prevent casual bypassing of this
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
* catalog, normalise catagories between addons that overlap
    - perhaps expand them into 'tags'? 
    - a lot of these categories are composite
        - break each composite one down into a singular, normalise, have a unique set of tags
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
