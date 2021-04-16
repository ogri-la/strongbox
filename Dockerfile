FROM archlinux:base

RUN pacman -Sy
RUN pacman -S jdk11-openjdk xorg-server-xvfb leiningen which gtk3 ttf-dejavu --noconfirm

RUN useradd user --create-home --user-group --groups wheel --uid 1000

WORKDIR /home/user

RUN mkdir strongbox
COPY src strongbox/src
COPY test strongbox/test
COPY cloverage.clj run-tests.sh project.clj strongbox/
RUN chown -R user:user strongbox/

USER user
WORKDIR /home/user/strongbox

RUN lein deps && lein cloverage -h > /dev/null

RUN ./run-tests.sh
