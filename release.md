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
* update/review CHANGELOG
    - ensure contents of TODO are captured succintly
    - remove 'unreleased'
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

* vagrant up
* cd strongbox
* rm -rf ./release/ strongbox ./target/ /vagrant/release/
* ./build-linux-image.sh
* mv ./strongbox ./target/*-standalone.jar ./release/
* cd release
* sha256sum strongbox > strongbox.sha256
* sha256sum strongbox-4.3.0-standalone.jar > strongbox-4.3.0-standalone.jar.sha256
* mv ./release /vagrant/
* update github release information
    - with changelog
    - with release files


* checkout develop
* merge changes from master
    - resolve conflicts etc
* update TODO, truncating old DONE
* update CHANGELOG, adding new sections
* update project.clj
    - change version to next version 
    - add "-unreleased" after it
* commit+push


* cd to strongbox-pkgbuild
* download strongbox.sha256 to /tmp
    - cat sumfile
* update PKGBUILD
    - change 'pkgver'
    - change 'sha256sums'
    - set pkgrel = 1
* run `makepkg --printsrcinfo > .SRCINFO`
* commit 
* push to github
    git push
* push to aur
    git push aur
