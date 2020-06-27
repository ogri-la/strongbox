# Contributing

I'm grateful you are interested in contributing.

## bugs

To get a bug looked at I must be able to recreate it.

1. create an [issue](https://github.com/ogri-la/strongbox/issues) to describe the bug

2. run the application with the `--debug` flag. For example, `strongbox --debug` or `java -jar strongbox.jar --debug`.

3. When the application exits, the version of strongbox and the path to the log file will be printed to the console. 
Include the version in the bug report and attach the log to the ticket. The log file will include your hostname and list 
of installed addons so make sure you're OK with that.

## feedback, feature requests

If you are a user of strongbox and you ever want to get in touch, please just 
[open an issue](https://github.com/ogri-la/strongbox/issues) or 
[PM me on reddit](https://www.reddit.com/message/compose/?to=torkus-jr&subject=strongbox

## code

Code contributions must meet some minimum requirements to be merged in. I can help with these.

1. You need to agree to the project's licence. I won't prompt you or make you sign anything, it will be taken for 
granted you understand the implications of the licence and agree with it's terms.

2. Pull requests rather than diffs, please. The base target should be `develop`.

3. Every line of your contribution will be reviewed. Many changes makes me less comfortable merging it.

4. Follow the code idioms and don't introduce anything too exotic or complicated. Think small incremental changes.

5. Tests! I'm a fan of them. Don't make me write tests for you.

6. I don't care to enforce coding style, use the `lint.sh` script and we'll live with it's decisions.
