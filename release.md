cd strongbox-release-script
vagrant up
vagrant ssh
cd strongbox-release-script
git reset --hard
git pull
./prep.sh <version>
review PR
merge
./release <version>

exit vagrant
vagrant halt

cd ../strongbox
git checkout master
git pull
git checkout develop
git merge master
lein clean

truncate TODO
update CHANGELOG with new sections from bottom
update project.clj with incremented version and "-unreleased"
update this doc with anything new
