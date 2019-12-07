# release notes

Just for my own reference

* exit emacs
* make sure nothing is left uncommitted
* run lein clean
* ensure all non-gpl dependencies have an exclusion in LICENCE
* update version in project.clj to the new version
    - remove the '-unreleased' extension
* update CHANGELOG
    - ensure contents of TODO are captured succintly
    - include new empty sections
* run lein pom
* commit+push
* open a pr for master
* wait for build to successfully complete, then merge
    - if build fails
        - fix fail
        - run `lein pom` again
        - commit+push
* checkout master+pull
* update README with expected path to download release
* if UI has changed significantly, add a screenshot
    - update README with link to new screenshot
* commit but DO NOT PUSH
* tag
    git tag 0.6.0
* push
    git push && git push --tags
* wait for release to appear
    - https://travis-ci.org/ogri-la/wowman
* update github release information with that from changelog

* checkout develop
* merge changes from master
    - resolve conflicts etc
* update TODO, truncating old DONE
* update project.clj, changing version to next version with "-unreleased" after it
* commit+push

* download release file to /tmp
* run sha256sum wowman-x.x.x-standalone.jar
* update wowman-pkgbuild/PKGBUILD
    - change 'pkgver'
    - change 'sha256sums'
* run `makepkg --printsrcinfo > .SRCINFO`
* commit 
* push to github
    git push
* push to aur
    git push aur
