# examples of Github addons supported by strongbox

A Github **release** may have many **assets**.

Each **asset** in a release must, at a minimum, be a fully uploaded zip file.

Each candidate asset is then inspected for the presence of 'retail' or 'classic' or both or neither.

The remote addon `.toc` file is looked for in the root of the code directory and it's `Interface` value inspected.

Below are some supported examples of each case.

## single asset, classic, no toc file, 'classic' in asset name
* https://github.com/wardz/ClassicCastbars

## single asset, classic, no toc file, no 'classic' in asset name
* https://github.com/DeadlyBossMods/DBM-Classic

## single asset, classic, toc file, 'classic' in asset name
* https://github.com/sylvanaar/ipop-bar

## single asset, classic, toc file, no 'classic' in asset name
* https://github.com/jsb/RingMenu
* https://github.com/Aviana/LunaUnitFrames

## single asset, retail, toc file
* https://github.com/ahakola/AzeritePowerWeights

## single asset, retail, no toc file
* https://github.com/RealUI/RealUI
* https://github.com/ascott18/TellMeWhen

## single asset, retail and classic (template .toc file), no 'classic' in asset name
* https://github.com/sylvanaar/wow-instant-messenger
* https://github.com/sylvanaar/prat-3-0
* https://github.com/sylvanaar/who-lib 
* https://github.com/Dreamwalker-Collective/faction-friend

## multi asset, retail and classic, 'classic' in asset name
* https://github.com/Stanzilla/AdvancedInterfaceOptions
* https://github.com/Ravendwyr/Chinchilla

## multi asset, retail and classic, no 'classic' in asset name

...?

# unsupported, but would love to support:

## no packages
* https://github.com/AdiAddons/AdiBags
* https://github.com/csundlof/ClassicHealAssignments

# unsupported, because ...

## no top-level addon directory in package
* https://github.com/Ostoic/RaidBrowser
