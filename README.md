# strongbox, a World of Warcraft Addon Manager

[![Build Status](https://travis-ci.org/ogri-la/strongbox.svg?branch=master)](https://travis-ci.org/ogri-la/strongbox)

`strongbox` is an **open source**, **advertisement free** and **privacy respecting** addon manager for World of Warcraft. 

It supports addons hosted by Curseforge, wowinterface, Tukui and Github.

If you are a user of strongbox and you ever want to get in touch, please just [open an issue](https://github.com/ogri-la/strongbox/issues) or [PM me on reddit](https://www.reddit.com/message/compose/?to=torkus-jr&subject=strongbox)

## Notice!

`wowman` has been renamed to `strongbox` for the (`1.0.0`) release.

Arch users will need to install the [strongbox AUR package](https://aur.archlinux.org/packages/strongbox) as updates to 
the `wowman` package will end with the `0.12.x` line.

## Audience

This software is for World of Warcraft players using Linux.

It also works on macOS.

It does not work on Windows. Windows will never be supported.

## Requirements

* Java 8+

## Installation

1. [download the jar](https://github.com/ogri-la/strongbox/releases/download/2.0.0/strongbox-2.0.0-standalone.jar) file
2. run with `java -jar strongbox-x.x.x-standalone.jar`

### Arch Linux users

A PKGBUILD exists in the AUR [here](https://aur.archlinux.org/packages/strongbox/) 
with a mirror [here](https://github.com/ogri-la/strongbox-pkgbuild/).

Once installed it's available from the command line as `strongbox`.

## Screenshots

[![wowman version 0.11.0](./screenshots/screenshot-0.11.0-installed-thumbnail.jpg)](./screenshots/screenshot-0.11.0-installed.png?raw=true) 
[![wowman version 0.11.0](./screenshots/screenshot-0.11.0-search-thumbnail.jpg)](./screenshots/screenshot-0.11.0-search.png?raw=true) 
[![wowman version 0.11.0](./screenshots/screenshot-0.11.0-dark-installed-thumbnail.jpg)](./screenshots/screenshot-0.11.0-dark-installed.png?raw=true) 
[![wowman version 0.11.0](./screenshots/screenshot-0.11.0-dark-search-thumbnail.jpg)](./screenshots/screenshot-0.11.0-dark-search.png?raw=true) 

*(dark mode only available in GTK+ 2 environments like [MATE](https://mate-desktop.org/) and 
[Cinnamon](https://en.wikipedia.org/wiki/Cinnamon_(desktop_environment)))*

## Usage

`strongbox` works by matching your installed addons to a list of addons available online.

Some addons match directly to those online but others require you to manually search and re-install them before that 
match can be made.

Some addons come bundled with other addons that *do not appear* in the online list. You will need to re-install the 
'parent' addon that bundles those addons.

First time usage:

1. select your "Addons" directory (`/path/to/WoW/_retail_/Interface/Addons`)
2. from the `Addons` menu select `Re-install all` to automatically re-install all **matching** addons
3. addons that are **not** automatically matched can be searched for and installed from the `search` tab
4. finally, addons can be deleted by selecting them, right-clicking, and selecting `delete`. Multiple addons can be 
selected and removed at once.

Afterwards, simply use the `Update all` button to update all addons with new versions available. 
Addons with new versions available will be highlighted.

## Recognition

Under no circumstances whatsoever does this software:

* deal with advertising or advertisers
* collect, monitor or report upon your usage of `strongbox` or your data
* attempt to monitise you, the user, in any way

This software also tries very hard to:

* be plain and uncomplicated
* do the least surprising thing
* clean up after itself
* not cause a bother - for you *or* the addon host

I benefit so much from the hard work of those who write free and open source software, including addon developers, 
that it's my privilege to offer this small piece back.

## Features

* classic and retail addon support
* catalogue search
* bulk update
* addons from multiple sources:
    - [curseforge](https://www.curseforge.com/wow/addons)
    - [wowinterface](https://wowinterface.com/addons.php)
    - [github](./github-addons.md) using *releases*
    - [tukui](https://www.tukui.org)
* import and export of lists of addons
* safeguards against bad addons
* warnings when addons install other bundled addons

## Notes

User configuration is stored in `~/.config/strongbox` unless run with the envvar `$XDG_CONFIG_HOME` set.

Temporary data is stored in `~/.local/share/strongbox` unless run with the envvar `$XDG_DATA_HOME` set.

Addon zip files are downloaded to your WoW `Addons` directory.

A file called `.strongbox.json` is created within newly installed or re-installed addons. This file maps specific 
attributes between the addon host (like curseforge.com) and the addon as well as *across* addons, as some addons unzip 
to multiple directories. It's a simple `txt` file in a structured format called `json`.

Addon zip files, `.strongbox.json` files, cached data and `WowMatrix.dat` files can all be removed from the `Cache` menu.

Addon `.zip` files that contain top-level files or top-level directories missing a `.toc` file **will not be installed**
and the downloaded `.zip` file will be deleted immediately. This is a guard against poorly or maliciously constructed
`.zip` files.

Addon `.rar` files are not supported.

This software interacts with the following remote hosts:

* Twitch (Curseforge) [Addons API](https://addons-ecs.forgesvc.net/) and it's [CDN](https://edge.forgecdn.net/)
* [wowinterface.com](https://wowinterface.com)
* [www.tukui.org](https://www.tukui.org/api.php)
* [api.github.com](https://developer.github.com/v3/repos/releases)
    - to download repository and release data for addons hosted on Github
    - to download the latest `strongbox` release data
* [github.com/ogri-la/strongbox-catalogue](https://github.com/ogri-la/strongbox-catalogue), to download addon catalogues

These hosts *may* redirect requests.

These interactions use a HTTP user agent header unique to `strongbox` so that it may be identified easily.

## Releases, bugs, questions, feedback, contributing

Changes are recorded in the [CHANGELOG.md](CHANGELOG.md) file.

All bugs/questions/feedback should go in [Github Issues](https://github.com/ogri-la/strongbox/issues) or 
via a Reddit [private message](https://www.reddit.com/message/compose/?to=torkus-jr&subject=strongbox).

All code contributions should take the form of a pull request with unit tests.  
[The licence](LICENCE.txt) is quite strict and all code contributions are subject to it.

See [CONTRIBUTING](CONTRIBUTING.md) for more detail.

## Other addon managers

**Moved here: https://ogri-la.github.io/wow-addon-managers/**

[Request a change](https://github.com/ogri-la/strongbox-comrades/issues). 

## License

Copyright © 2018-2020 Torkus

Distributed under the GNU Affero General Public Licence, version 3 [with additional permissions](LICENCE.txt#L665)
