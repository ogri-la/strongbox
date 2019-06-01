# todo.md

this is my own scratchpad for keeping track of things. it gets truncated frequently.

see CHANGELOG.md for a more formal list of changes by release

## 0.7.0 release

### done

* refuse to run as the root user
    - done
* add new field 'age' to an addon
    - done
* support wowinterface.com
    - addon data scraped and available in wowman-data
        - done
    - unintrusive scrape of most recently updated addons
        - this looks like the ticket: https://www.wowinterface.com/downloads/latest.php
            - goes back several months
        - done
    - single muxed 'catalog' of addons from different sources
        - why provide multiple sources for addons?
            - greater range of addons available to install
            - users may prefer one source over another
        - what is the extent of the overlap?
            - join on :name and see
        - two approaches:
            - single list of addons with a :source attribute
                - *probably* a lot of duplicates in search
                    - won't know until I join on :name and see
                - some might prefer the explicitness of seeing the two same addons side-by-side
                    - you would see different download counts
                    - two distinct links
                    - you could remove the results of one in favour of the other
                        - this would undermine providing a greater range of addons for users
                        - could we discard only those results where multiple sources exist in favour of preferred?
                            - this seems like an interesting optimisation we could add later
                                - a drop down in search options: 
                                    "just curseforge", "just wowinterface", "prefer curseforge", "prefer wowinterface"
                                - preferring one source over another would see cases where multiple sources for a single addon has one elided
                                - this list would grow quickly if we add many more sources ...
                    - we *are* targetting a linux audience by the way
                        - minimal hand holding
                    - upgrade path to clojure's defrecord and would be straightforward
            - grouped list where each addon may have multiple sources
                - less noise in search results
                    - but won't truly know until we join on :name and see
                - would have to deal with:
                    - user setting a preferred :source
                        - do they care? 
                    - merging the contents of the two
                    - creating a policy of wowman preferring one :source over another
                        - not just to download, but in which information to present to user
                        - I don't really want to pick sides
                - it may go against stated goals of:
                    * be plain and uncomplicated
                    * do the least surprising thing
        - I think a single list with potential duplicates from different sources is the way to go for now
        - how to merge catalogs into a single catalog
            - :datetime-created becomes the date the catalog was muxed
                - no, I decided it should be either completely-derived or completely new
                - went with derived for now
                - done
            - :updated-date becomes the highest :updated-date of the two catalogs
                - they should be very close anyway
                - done
            - every addon gets a :source attribute
                - done
            - remove the :altname from curseforge
                - done
            - add :altname and any other derived fields
                - done
            - exclude addons that haven't been updated in N years (2? 5?)
                - I think this would be useful for the addons that overlap
                - in some cases there are years of difference between updated dates between the two sources
                    - after investigation:
                        - curseforge is much more preferred than wowinterface for the 1178 addons that overlap
                        - a value around ~360 minutes difference keep reappearing
                            - I suspect the wowinterface datetime offset is +/- 6 hours
                            - probably -6 as this would place it firmly in the US
                                - but the value is +6, which is the middle east, so nfi
                            - curseforge dt is constructed from a unix-time timestamp, which is UTC, so I trust that
                - done
                    - settled on 2 years difference between :updated-dates, removing about ~270 duplicates
                    - this seems wide enough to consider one or the other abandoned and save us some noise
                        - dropping down to 1 month removes about twice as many.
            - generate rss/atom feeds?
                - not in this release
            - generate a database?
                - not in this release
        - done
    - fetch full addon data (expand-summary)
        - done
    - download the wowinterface addon
        - done
    - change contents of 'go' column in installed+search fields
        - must be the value of :source above
        - done
    - when matching addons 
        - we never had multiple :name joins before with curseforge, now we have 2k+ of them
        - when loading a .wowman.json file, we need to add a :source with "curseforge" if :source is missing
            - we'll use this when matching the installed addon to the catalog
            - this will help me avoid preferring one host over another
                - or matching an older version of an addon on one catalog with that in another
            - nothing can be done to avoid a preference when matching unknown addons against the catalog
                - except perhaps randomising the addon picked.
                - I prefer deterministic results though
        - done
    - when installing an addon, set the source of the addon
        - done
    - all done
* refactor, rename fs.clj to toc.clj
    - done
* handling loading of bad json files better
    - empty and malformed json files just error out
        - a simple warning and a default could prevent this
    - done
        - handling present for "no-file", "bad-data" and "invalid-data"
        - several (not all) cases now handled

### todo

* cli, data path is now broken after moving to XDG paths
    - I just realised.
    - this is affecting tests as well, as they're picking up on config outside of temp dirs

## todo bucket

* backups
    - wowman is strictly an addon manager, not an auxillary WoW manager
        - I won't be backing up screenshots or addon state or anything like that
    - wowman could maintain a simple list of addons to restore
        - it might tie in with the 'export' function below
* catalog, normalise catagories between addons that overlap 
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
* automatically exclude 'ancient' addons from search results
    - these are addons that haven't been updated in ~18 months
        - wowinterface has a lot of them
* group search results?
    - group by downloads/age/category?
        - it would finally be the best use for category data
* cache, make caching opt-out and remove all those ugly binding calls
    - bind the value at core app start
* windows support
    - eh. I figure I can do it with a VM. I just don't really wanna.
* gui, search, deselect selected addons after successful installation
* curseforge, addons whose :name changes
    - see 'speedyloot' that changed to 'speedyautoloot'
        - they share the same creation date
        - /speedyloot now redirects to /speedyautoloot, so curseforge definitely have support for name changing
* memory usage
    - we're big and fat :(
    - lets explore some ways to measure and then reduce memory usage
    - measuring:
        - https://github.com/clojure-goes-fast/clj-memory-meter
* 'export' addons
    - an idle thought until I saw wowmatrix has it
        - they have a wordpress plugin and a simple text file
    - json, yaml and xml serialisations as a minimum
        - these are the most common and versatile
    - friendly text and html formats
        - who on earth would use such a thing? and is it worth the added complexity?
* code quality, we're sorely lacking in tests and test coverage metrics
* move away from this merging toc/addon/expanded addon data strategy
    - it's confusing to debug!
    - namespaced keys might be a good alternative:
        - :toc/label and :catalog/label
        - :toc/version and :catalog/version
        - with derived/synthetic attributes having no ns
            - :group-id, :group-count
        - how to pick preferred attributes without continuous (or key else other-key) ?
            - (getattr addon :label) ;; does multiple lookups ...? seems kinda meh
* revisit group records, I can't believe we can't pull a good name or description from *somewhere*
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
* gui, feature, install addon from local zipfile
    - *not* the 'reinstallation' feature, but literally selecting a zipfile from somewhere and installing it
    - would be good for installing older versions of an addon?
* gui, scroll tabs with mouse
* gui, search, order by date only orders the *current page* of results
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
* allow user to specify their own catalog

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
