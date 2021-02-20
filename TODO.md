# todo.md

this is my own scratchpad for keeping track of things. it gets truncated frequently.

see CHANGELOG.md for a more formal list of changes by release

## done

* remove gui1
    - done
    - remove original db-search
        - done

## todo

* gui, new tab for dedicated log
* per-addon logging
    - I want the user to see a list of messages regarding that *specific* addon
    - when emitting a log line about a particular addon, capture that addon's source and source-id somehow
* gui, download progress bar *inside* the grid ...?
    - pure fantasy?
    - defer until after job queue
* greater parallelism
    - internal job queue
    - replace log at bottom of screen with a list of jobs being processed and how far along they are
        - each job can be cancelled/stopped/discarded
    - separate tab for log
        - that scrolls the other way
* a 'stop' button to stop updates would be nice
* download addon details in parallel
    - speed benefits, mostly
    - share a pool of connections between threads
        - N connections serving M threads
* performance, check addons for updates immediately after loading
    - if after we've read the nfo data and we have everything we need, check the addon for updates immediately
        - don't wait for db loading and addon matching
            - we already have a match!
        - this might fit in with the greater-parallelism/queue based infrastructure
* remove 'hostname' from log output
    - update privacy section in readme
* bug, 'strongbox.version' in debug output is null when run as a binary

## todo bucket (no particular order)

* cli, exports

* alpha/beta opt-in
    - user can opt to install alpha/beta/no-lib releases per-addon

* import, export, capture 'pinned' information
    - we can now import addons at a specific version
        - when importing a pinned addon, should we keep the pin? 
        - or drop the pin it and display updates?
    - we can now export addons at specific versions
        - I think we already have this though ... called :version

* github, revisit the "-classic" suffix naming
    - this was solved more elegantly in curseforge-api

* dedicated tab for "user-catalogue" ?
    - add, delete, update github addons
    - see accumulating release history for addons?

* preferences, "update all addons automatically"
    - update README features

* import, skip importing an addon if addon already exists in addon dir

* import, why can't an export record be matched to the catalogue and then installed that way?
    - no need for padding and dummy dirnames then
    - installing normally would also include the mutual dependency handling

* toc, add support for x-github key
    - X-Github: https://github.com/teelolws/Altoholic-Retail 

* create a parser for that shit markup that is preventing reconcilation
    - see aliases

* if a match has been made and the addon installed using that match, and then the catalogue changes, addon should still be downloadable
    - right?
        - we have the source and source-id, even the group-id to some extent
    - switching catalogues may see the addon matched against another host
        - nothing wrong with that, but ...

* http, add a timeout for requests
    - I have tukui API taking a looooong time

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
        - 2020-11-28: unparking this
        - since this was parked I've added source-id and source as standard across all addons
            - user just needs to copy and paste a url, strongbox matches it against catalogue, and installs it.

* gui, feature, install addon from local zipfile
    - *not* the 'reinstallation' feature, but literally selecting a zipfile from somewhere and installing it
    - would be good for installing older versions of an addon?
    - would be good for installing addons from unsupported sources
        - wouldn't be able to update it however :(
        - I think I'll stick with supporting sources of addons 
            - rather than enabling ad-hoc installation of unsupported addons

* search, results not updated when catalogue is changed

* gitlab as addon host
    - https://gitlab.com/search?search=wow+addon
    - returned to bucket 2019-12-04, notes:
        - gitlab doesn't handle releases like github does
            - https://stackoverflow.com/questions/29520905/how-to-create-releases-in-gitlab
        - there are very few gitlab addons (88)
            - where did this number come from?
        - api is quite slow
    - update: as of Oct 2020 gitlab sucks a little bit less and, like github, you can attach binaries to releases
        - https://gitlab.com/explore/projects?tag=World+of+Warcraft
        - https://gitlab.com/shrugal/PersoLootRoll
        - any others ...?

* investigate *warn-on-reflections*
    - I think there may be some solid performance gains by turning this on
        - remember to profile first

* EOL planning, robustness, only download/update the catalogue *after* an existing catalogue has been confirmed
    - github is down, wowman is erroring with a 500
    - failure to download a catalogue shouldn't prevent addons from being displayed
    - failure to contact a host shouldn't prevent addons on other hosts from working

* EOL planning, bundle a catalogue with the installation
    - load it as a resource with static-slurp, like we do with the sql?
        - also compressed so it's tiny?
    - behind the scenes we download and load the full-catalogue
        - would this block reconciliation?
            - perhaps if there are unmatched addons after reconciliation we then wait and try again ...?

* add checksum checks after downloading
    - curseforge have an md5 that can be used
        - unfortunately no checksum in api results
        - they do have a 'fileLength' and a 'fingerprint'
            - fingerprint is 9 digits and all decimal, so not a hex digest
    - wowinterface checksum is hidden behind a javascript tabber but still available
        - wowinterface do have a md5sum in results! score

* test, can gui-diff and main/test be pushed back into the testing namespace and elided from release somehow?

* EOL planning
    - I'm not going away and neither is strongbox, but! *should* I or my free time disappear will strongbox continue being useful?
        - what can I do to ensure it is the most useful if I just give up on it tomorrow?
            - catalogues have been pushed into user config, so they can be swapped out if necessary
                - more can be done around this to make catalogue generation more accessible and safe
        - what can't I control?
            - addon hosts
                - our interface with them is their API or in wowi's case, their API and website

* reconciliation, add dirname support
    - not sure which hosts support these
* wowinterface, multiple game tracks 
    - investigate just what is being downloaded when a classic version of a wowi addon is downloaded
    - see 'LagBar'

* bug, changing sort order during refresh doesn't reflect which addon is being updated
    - I think changing column ordering and moving columns should be disabled while updates happen
        - just freeze or disable them or something.
* when curseforge api is down users get a wall of red error messages with very little useful information
    - see issue 91: https://github.com/ogri-la/wowman/issues/91
        - the error message has been improved but we still get a red wall of text
        - aggregate error messages?
* reconciliation, rename 'reinstall all' to 'reconcile'
    - steal from the best
    - make the reconcile automatic
        - if a nfo file isn't found
    - remove the 'first time instructions' from the readme
        - it should just fucking do it
* investigate better popularity metric than 'downloads'
    - if we make an effort to scrape everyday, we can generate this popularity graph ourselves
* add a 'tabula rasa' option that wipes *everything* 
    - cache, catalog, config, downloaded zip files
* coloured warnings/errors on console output
    - when running with :debug on the wall of text is difficult to read
    - I'm thinking about switching away from timbre to something more traditional
        - he's not addressing tickets
        - it may have been simpler to use in 3.x.x but in 4.x.x it's gotten a bit archaic
        - I can't drop hostname without leaving pretty-printed stacktraces behind
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

* gui 'wow' column is inconsistent
    - for curseforge, it's pulling it's value from :gameVersion, which may be empty
        - in which case it pulls it's value from the toc file, which may be different from the selected game track
    - since this is the 'installed addons pane', should the value reflect the value of the installed addon?
        - (and not the value of the addon to be installed)
        - and would this be inconsistent with the other fields that are also changing with new catalog information?

* gui, toggleable columns as a menuitem

* internationalisation?
    - Akitools has no english description but it does have a "Notes-zhCN" in the toc file that could be used
    - wowman was mentioned on a french forum the other day ..

* gui, toggleable highlighers as a menuitem
    - highlight unmatched
    - highlight updates
    - touch of colour against each menuitem would serve as a legend
* gui, have the log scroll the other direction

## github

* github, add support for user supplied github token
    - necessary if they want a large number of github addons without hassles
* github, add a github catalogue
    - just a simple list of wow addons on github that can be installed with strongbox
* github, importing an exported addon list with a github addon won't see that addon installed
    - unless that addon is present in the user catalogue
        - which in a fresh install where a list of addons are being restored is unlikely...
    - this is interesting actually. the exported addon list has become a mini-catalogue
        - some addons require the larger catalogue to resolve
        - github addons are resolved and installed by a different means...

## import/export

* export to markdown
    - I think I'd like a simple list like:
        * [addon name](https://source/path/to/addon)
    - of course, this would be a different type of export than the one used for import
        - although ... I could possibly parse the list ... and nah.
    - clostache?
        - https://github.com/fhd/clostache

## search

* search, order by date only orders the *current page* of results
* search, group results
    - group by downloads/age/category?
        - it would finally be the best use for category data

## cli

* cli, update specific addon
* cli, install specific addon
* cli, colours!
* cli, replace with a repl
    - lein --cli gives you access to the code directly

## unified UI

* remove log split
* remove tabs
* gui, both panes, filter by categories
* gui, group results
    - installed
    - updates
    - category ...

## wontfix

* add a 'Delete all' option to cache menu
    - we don't really want legitimate nfo files to be accidentally deleted
* nightly unstable builds
    - building the 'develop' branch once a day
        - making it available as the 'unstable' release that always gets replaced
    - project.clj "x.y.z-unreleased" would be changed to "x.y.z-unstable"
    - development would happen mainly in feature branches
    - too much effort for what? more user reports? I don't have that sort of time
* investigate `.csv` as a human-readable but more compact representation
    - might be able to save a MB on extraneous syntax
    - might be able to speed up parsing and loading
    - depends on profile task
        - update: profiling happened, it's not the json loading that is slow it was many other things. 
        - reading the file and parsing the json is actually very quick, validation is slower but necessary
        - json is just more flexible all around than csv.
* windows support
    - windows is just the worst, most awful dystopian software I've ever seen and it hurts my soul every time I try to use it
    - I just plain hate it, it epitomises the very opposite of what I stand for and I refuse to work on it ever again
* addon 'detail' tab
    - link to curseforge
    - donation url
    - other addons by author ?
    - list the hidden/sub dependencies
    - too vague, too open ended, too much effort
        - just send them to the official addon page
* .rar/.tar.gz addons
    - !BeautyLoot on wowinterface is an example of this
        - https://www.wowinterface.com/downloads/info20212
    - rar is a proprietary format
    - the vast majority of addons use .zip
    - no native support in java/clojure for it
        - library here: https://github.com/junrar/junrar
            - just found it while going through minion source
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
    - 2020-11-28: I think this was about being able to download catalogues (curseforge.json) when github is down
        - if a catalogue is embedded then we can always fall back to that
        - see EOL planning
* gui, search pane, clear search button
    - I don't think this is necessary anymore
* gui, stateful buttons
    - don't allow enabled 'delete selected' buttons if nothing is selected
    - not going to coddle the user. deleting nothing will see nothing deleted.
* cli, interactive interface when no specific action specified 
    - you have N addons installed. what do you want to do? (list, update, update-all, delete) etc
    - this is a huge amount of hassle
