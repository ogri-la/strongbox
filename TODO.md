# todo.md

this is my own scratchpad for keeping track of things. it gets truncated frequently.

see CHANGELOG.md for a more formal list of changes by release

## done

* change installation from 'overwrite' to 'uninstall+install'
    - addons are uninstalled before they are installed
        - done
* strengthened 'ignore' rules
    - ignored addons and any bundled addons cannot be overwritten by strongbox
    - ignored addons cannot be uninstalled
    - ignored addons can be unignored from the gui
    - addons can be ignored from the gui
* rename 'wowman-comrades' to 'strongbox-comrades'
* add ability to explicitly unignore addon from context menu
* wowman-data, stop publishing a 'daily' release
    - we have multiple catalogs now
    - 0.10.0 uses the raw catalog files directly
    - 0.9.2 was still using the daily release
    - done
* change installation from 'overwrite' to 'uninstall+install'
    - what to do about mutual dependencies?
        - i.e., two addons both include some addon, one overwrites the other, that one is uninstalled leaving the other in a broken state. 
        - Mutual dependencies aren't tracked ... 
        - I could:
            - detect if an addon were to be replaced by another addon in a different group
            - if so, attach the details of the addon with the replaced group
            - if the addon gets uninstalled, the other addon is re-installed
            - caveats:
                - what about three or more addons all relying on the same sub-addon?
                    - the list accumulates
                    - as each one is uninstalled, it gets removed from the list and the top-most is re-installed
                - what if an ignored addon is relying on a sub-addon?
                    - ignored addons shouldn't be automatically uninstalled or reinstalled
                    - ignored addons may block the installation/uninstallation of others
        - I could also:
            - not remove a mutual dependency
                - if A and B depend on C
                - and B installed C last
                - and then B is removed
                - A is left depending on C that it didn't install
                    - it could be of a different version ...
                    - this is no different to the current situation
            - the mutual dependency has it's group identity updated
                - or we keep a list of group membership

* bug, reinstall is busted
    - looks like I've been relying on the selected table rows to be returning correct data
        - it's not. it's been mangled and padded to suit the gui
        - and now the ignore flag value isn't consistent with the proper data

* just encountered a case where the classic version overwrote one of the retail directories but not the other
    - (tukui classic and retail?)
    - so there was a broken retail installation but a working classic installation
        - I was able to 'uninstall' the broken retail installation without a problem
    - this is still a muddy state of affairs, but it's handled cleanly and predictably now
        - the user is warned that an addon is overwriting another
        - removing one will reveal the other
            - this is a 'masking' effect I hadn't anticipated
            - it could still be confusing but I suspect it's pretty rare

* removed support for migrating wowman-era config and data

* issue 169, handle 5xx errors from curseforge and others predictably

* issue 166, lengthen the addon directory dropdown

* bug, new gui instance is spawned when switching themes outside of the REPL

## todo


## todo bucket (no particular order)

* gui, add confirmation before deleting addon directory

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

* spec, revisit usage of 'addon/toc'
    - it was used too broadly before the recent spec shakeup
    - it has optional keys which shouldn't be in there

* gui, 're-install' for an addon that has an update available will update the addon
    - it implies the same version would be installed
    - strongbox doesn't support version pinning yet either

* gui, context menu has 'n selected, m updatable'
    - this is cute but not useful
    - selecting this doesn't perform any action

* github, if multiple releases available and first fails criteria, check the next and so on
    - see altoholic: https://github.com/teelolws/Altoholic-Classic

* test, can gui-diff and main/test be pushed back into the testing namespace and elided from release somehow?

* http, revisit the http/expiry-offset-hours value
    - also, revisit prune-http-cache

* EOL planning
    - I'm not going away and neither is strongbox, but! *should* I or my free time disappear will strongbox continue being useful?
        - what can I do to ensure it is the most useful if I just give up on it tomorrow?
            - catalogues have been pushed into user config, so they can be swapped out if necessary
                - more can be done around this to make catalogue generation more accessible and safe
        - what can't I control?
            - addon hosts
                - our interface with them is their API or in wowi's case, their API and website

* code refactor
    * core.clj is getting too large
        - it's difficult to navigate and debug
        - many tests are accumulating in core_test.clj

* reconciliation, revisit aliases
    - use source and source-id now
    - maybe externalise the list
* reconciliation, add dirname support
    - not sure which hosts support these
* wowinterface, multiple game tracks 
    - investigate just what is being downloaded when a classic version of a wowi addon is downloaded
    - see 'LagBar'
* version pinning
    - user can opt to install a specific release of an addon
    - automatic updates for that addon are thereafter blocked
* alpha/beta opt-in
    - user can opt to install alpha/beta/no-lib releases per-addon
* bug, changing sort order during refresh doesn't reflect which addon is being updated
    - I think changing column ordering and moving columns should be disabled while updates happen
        - just freeze or disable them or something.
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
* gui 'wow' column is inconsistent
    - for curseforge, it's pulling it's value from :gameVersion, which may be empty
        - in which case it pulls it's value from the toc file, which may be different from the selected game track
    - since this is the 'installed addons pane', should the value reflect the value of the installed addon?
        - (and not the value of the addon to be installed)
        - and would this be inconsistent with the other fields that are also changing with new catalog information?

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

* toggleable columns as a menuitem
    - they're available from the column menu, but it's a little hidden and contains other fairly useless options like 'horizontal scroll'
* gui, both panes, filter by categories
* internationalisation?
    - Akitools has no english description but it does have a "Notes-zhCN" in the toc file that could be used
    - wowman was mentioned on a french forum the other day ..

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

## backups



## import/export

* export to markdown
    - I think I'd like a simple list like:
        * [addon name](https://source/path/to/addon)
    - of course, this would be a different type of export than the one used for import
        - although ... I could possibly parse the list ... and nah.
    - clostache?
        - https://github.com/fhd/clostache

## search

* search, indicate results are paginated
* search, order by date only orders the *current page* of results
* search, pagination controls in search pane
* search, group results
    - group by downloads/age/category?
        - it would finally be the best use for category data

## cli

* cli, update specific addon
* cli, install specific addon
* cli, colours!
* cli, replace with a repl
    - lein --cli gives you access to the code directly

## job queue

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

## gui/gui2

* gui2
    - an OpenJFX gui
    - how large is bundle after uberjar?
    - can an openjfx-11/openjdk-11 uberjar be run with openjdk 8?
        - if not, then that is a hard upgrade for users :(
            - unless we do a completely standalone version?
                - this would depend on the modularisation introduced in java 9
                - min JRE is 29MB, about ~9MB more than what we already have.
                    - is filesize a problem for users?
* gui, java look and feel
    - our 'theme' solution is too naive
        - we should be deferring to the current theme for highlighted colours
        - how?
            - https://pirlwww.lpl.arizona.edu/resources/guide/software/SwingX/org/jdesktop/swingx/plaf/UIColorHighlighterAddon.html
    - defer until after gui2
* download progress bar *inside* the grid ...?
    - pure fantasy?
    - defer until after gui2
    - defer until after job queue
* add custom highlighting colours
    - I don't mind my colours but not everybody may
    - my colours don't work very well on native lnf + dark themes:
        - https://github.com/ogri-la/wowman/issues/105
    - defer until after gui2
* toggleable highlighers as a menuitem
    - highlight unmatched
    - highlight updates
    - touch of colour against each menuitem would serve as a legend
* have the info box scroll the other direction
    - this is possible, see the seesaw examples
* new tab for dedicated log

## wontfix

* nightly unstable builds
    - building the 'develop' branch once a day
        - making it available as the 'unstable' release that always gets replaced
    - project.clj "x.y.z-unreleased" would be changed to "x.y.z-unstable"
    - development would happen mainly in feature branches
    - too much effort for what? more user reports? I don't have that sort of time
* wowman, add support for reading strongbox catalogues
    - I have the strongbox-catalogue update.sh script building wowman catalogues
    - there is no extra work involved
* wowman, remove wowman-data repository
    - eh, this won't be happening any time soon
    - it's no extra work to maintain it
* investigate `.csv` as a human-readable but more compact representation
    - might be able to save a MB on extraneous syntax
    - might be able to speed up parsing and loading
    - depends on profile task
        - update: profiling happened, it's not the json loading that is slow it was many other things. 
        - reading the file and parsing the json is actually very quick, validation is slower but necessary
        - json is just more flexible all around than csv.
* remove backwards compatibility, wowman to strongbox
    - there will be a migration of wowman data to strongbox data, or the data is discarded
        - rename `test/fixtures/user-config-0.11.json` to `wowman--user-config ...`
    - decided against this
        - providing backward compatibility and testing against previous versions of data shapes gives us:
            - robustness
            - a reminder of what the state was like and what we've done previously to handle it
            - seamless upgrades between versions for users
                - they (including myself) shouldn't have to deal with bs 'technical' reasons why it used to work but not longer does
                    - we're just not *that* special
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
