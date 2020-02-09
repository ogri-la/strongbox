# todo.md

this is my own scratchpad for keeping track of things. it gets truncated frequently.

see CHANGELOG.md for a more formal list of changes by release

## 1.0.0 release

### done

* code refactor
    - diagram state transitions
        - my mental model has become fuzzy
        - done, see strongbox-docs
    - untangle nfo and toc files
        - result of diagramming modules
        - done
    - rename references of 'uri' to 'url'
        - these are all through the catalog
        - done
    - remove all mentions of a donation url, author name
        - done

* can addon-id be removed as a gui column?
    - no.
    - addon-id is actually "name"
    - "name" is the value core.clj uses to update the 'unsteady addon' list that the gui watches to highlight rows
    - "label" is actually masquerading as "name"
    - won't 'fix'

### todo

* rename wowman
    - rename repository
    - update readme
        - mention name change prominently
    - update package
        - add new shell script 'strongbox'
    - remove 'alt-name'
        - done

* catalog updates
    - rename references of 'uri' to 'url'
        - these are all through the catalog

    - normalise categories between addon hosts
        - perhaps expand them into 'tags'?
        - a lot of these categories are composite
            - break each composite one down into a singular, normalise, have a unique set of tags

    - can game-track-list be included from all other hosts?
        - not just wowi?
    
    - publish a 'strongbox-catalogue' repo
        - just like wowman-data, but for strongbox

    - move location of catalogs into user settings
        - allow user to specify their own catalogs
            - a url to a catalog that is downloaded and included while loading up the db
            - different from the 'user catalog'
        - wowman-data, stop publishing a 'daily' release
            - we have multiple catalogs now
            - 0.10.0 uses the raw catalog files directly
            - 0.9.2 was still using the daily release
            - remove the 'daily' release after 0.11.0 is released
            - this will break older releases but users who prefer older versions of the software shouldn't be stranded if the catalog goes away
                - they should just be able to plug in a new location of the catalog
                - unfortunately *these* users will be out of luck, but future users won't be
            - I'll stop updating wowman-data when wowman is no longer being used

* remove backwards compatibility kludges
    - there will be a migration of wowman data to strongbox data

* spec clean up
    * it's never been particularly clear in my head what some of those specs are
    * I have a better understanding of their nature now
        - as part of the diagramming, sketch out the fields to be captured

* database, investigate a datalog backed datastore
    - https://clojure.github.io/clojure-contrib/doc/datalog.html
    - https://github.com/tonsky/datascript
    - I want addons loaded *quickly*
    - I want to *query* addons *quickly*

* code refactor
    * simplify `install-addon` interface in core.clj
        - we need to provide an installation directory which can be pulled from the application state
    * core.clj is getting too large
        - it's difficult to navigate and debug
        - many tests are accumulating in core_test.clj

## todo bucket (no particular order)

* add dirname support to reconcilation and catalogue
    - not sure which hosts support these
* tukui and elvui can't be switched to classic
    - on classic track they show updates
        - elvui 1.82 => 1.211
        - tukui 4.42 => 1.321
    - but updating them doesn't alter their reported versions
        - the 'source' for these two are 'tukui-classic', the others are just 'tukui'
    - problem seems to be in the :version and :installed-version attributes
        - after refresh, the :version attribute is correct but :installed-version is still incorrect
    - I have addons masking other addons!
        - ElvUI_MerathilisUI/ was masking Tukui
        - ElvUI_CodeNameBlaze/ was masking Elvui
    - the addons were being updated, but were being mis-matched during the database search because of shared IDs ...?
        - ids are 1 and 2
        - I thought these were negative? or I made them negative?
        - anyway
* wowinterface, multiple game tracks 
    - investigate just what is being downloaded when a classic version of a wowi addon is downloaded
    - see 'LagBar'
* revisit aliases
    - use source and source-id now
    - maybe externalise the list 
* greater parallelism
    - internal job queue
    - replace log at bottom of screen with a list of jobs being processed and how far along they are
        - each job can be cancelled/stopped/discarded
    - separate tab for log
        - that scrolls the other way
* version pinning
    - user can opt to install a specific release of an addon
    - automatic updates for that addon are thereafter blocked
* alpha/beta opt-in
    - user can opt to install alpha/beta/no-lib releases per-addon
* gui, java look and feel
    - our 'theme' solution is too naive
        - we should be deferring to the current theme for highlighted colours
        - how?
            - https://pirlwww.lpl.arizona.edu/resources/guide/software/SwingX/org/jdesktop/swingx/plaf/UIColorHighlighterAddon.html
* bug, changing sort order during refresh doesn't reflect which addon is being updated
    - I think changing column ordering and moving columns should be disabled while updates happen
        - just freeze or disable them or something.
* download progress bar *inside* the grid ...?
    - pure fantasy?
* add support for user supplied github token
    - necessary if they want a large number of github addons without hassles
* investigate state of java packaging
    - https://www.infoq.com/news/2019/03/jep-343-jpackage/
* add an option that forces installation of addon if matching game track not found
    - enable it by default
    - add a warning when installing an addon that doesn't match game track
    - add a summary after each refresh about the state of installed addons
        - "123 addons installed, 1 unmatched addon, 2 retail addons installed"
        - "123 addons installed, 2 classic addons installed"
    - preserve this in user settings
    - perhaps couple this with the GUI logic for the status bar down the bottom
* github, importing an exported addon list with a github addon won't see that addon installed
    - unless that addon is present in the user catalogue
        - which in a fresh install where a list of addons are being restored is unlikely...
    - this is interesting actually. the exported addon list has become a mini-catalogue
        - some addons require the larger catalogue to resolve
        - github addons are resolved and installed by a different means...
* add custom highlighting colours
    - I don't mind my colours but not everybody may
    - my colours don't work very well on native lnf + dark themes:
        - https://github.com/ogri-la/wowman/issues/105
* when curseforge api is down users get a wall of red error messages with very little useful information
    - see issue 91: https://github.com/ogri-la/wowman/issues/91
        - the error message has been improved but we still get a red wall of text
        - aggregate error messages?
* new tab for dedicated log
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

* export to markdown
    - I think I'd like a simple list like:
        * [addon name](https://source/path/to/addon)
    - of course, this would be a different type of export than the one used for import
        - although ... I could possibly parse the list ... and nah.
    - clostache?
        - https://github.com/fhd/clostache
* add a 'tabula rasa' option that wipes *everything* 
    - cache, catalog, config, downloaded zip files
* coloured warnings/errors on console output
    - when running with :debug on the wall of text is difficult to read
    - I'm thinking about switching away from timbre to something more traditional
        - he's not addressing tickets
        - it may have been simpler to use in 3.x.x but in 4.x.x it's gotten a bit archaic
        - I can't drop hostname without leaving pretty-printed stacktraces behind

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

* memory usage
    - we're big and fat :(
    - lets explore some ways to measure and then reduce memory usage
    - measuring:
        - https://github.com/clojure-goes-fast/clj-memory-meter
        - https://visualvm.github.io/
* toggleable highlighers as a menuitem
    - highlight unmatched
    - highlight updates
    - touch of colour against each menuitem would serve as a legend
* toggleable columns as a menuitem
    - they're available from the column menu, but it's a little hidden and contains other fairly useless options like 'horizontal scroll'
* nightly unstable builds
    - building the 'develop' branch once a day
        - making it available as the 'unstable' release that always gets replaced
    - project.clj "x.y.z-unreleased" would be changed to "x.y.z-unstable"
    - development would happen mainly in feature branches
* a 'stop' button to stop updates would be nice
* download addon details in parallel
    - speed benefits, mostly
    - share a pool of connections between threads
        - N connections serving M threads
* search, indicate results are paginated
* search, order by date only orders the *current page* of results
* search, pagination controls in search pane
* search, group results
    - group by downloads/age/category?
        - it would finally be the best use for category data
* cli, update specific addon
* cli, install specific addon
* cli, colours!
* gui, both panes, filter by categories


## post 1.0

* internationalisation?
    - Akitools has no english description but it does have a "Notes-zhCN" in the toc file that could be used
    - wowman was mentioned on a french forum the other day ..
    - post 1.0
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

## wontfix

* windows support
    - windows is just the worst, most awful dystopian software I've ever seen and it hurts my soul every time I try to use it
    - I just plain hate it, it epitomises the very opposite of what I stand for and I refuse to work on it ever again
* gitlab as addon host
    - https://gitlab.com/search?search=wow+addon
    - returned to bucket 2019-12-04, notes:
        - gitlab doesn't handle releases like github does
            - https://stackoverflow.com/questions/29520905/how-to-create-releases-in-gitlab
        - there are very few gitlab addons (88)
        - api is quite slow
    - if somebody asks for this especially I'll consider it
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
