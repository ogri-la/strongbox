# release notes

Just for my own reference

* exit emacs
* make sure nothing is left uncommitted
    - git reset --hard
    - git pull
* run
    - lein clean
* new branch '0.x.0-prep'
* ensure all non-gpl dependencies have an exclusion in LICENCE
* if major version change, update
    - SECURITY.md
* update version in project.clj to the new version
    - remove the '-unreleased' extension
* update CHANGELOG
    - ensure contents of TODO are captured succintly
    - include new empty sections
* update README with expected path to download release
* if UI has changed significantly, add a screenshot
    - update README with link to new screenshot
* run lein pom
* commit + push
* open a pr for MASTER branch
* review the changes to be merged in to master!
    - release 0.12.1 was completely missing the intended fix!
* wait for build to successfully complete
    - if build fails
        - fix fail
        - run `lein pom` again
        - commit+push
* test on mac
* merge PR branch
* checkout master + pull
* tag
    git tag 0.6.0
* push
    git push --tags
* wait for release to appear
    - https://travis-ci.org/ogri-la/strongbox
* update github release information with that from changelog


* checkout develop
* merge changes from master
    - resolve conflicts etc
* update TODO, truncating old DONE
* update project.clj
    - change version to next version 
    - add "-unreleased" after it
* commit+push


* download release sum file to /tmp
    - cat sumfile
* update strongbox-pkgbuild/PKGBUILD
    - change 'pkgver'
    - change 'sha256sums'
* run `makepkg --printsrcinfo > .SRCINFO`
* commit 
* push to github
    git push
* push to aur
    git push aur
