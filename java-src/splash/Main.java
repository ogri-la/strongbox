/*
  MIT Licensed, see LICENCE.txt for cljfx GPLv3 exclusion.
  copied from: https://github.com/cljfx/cljfx/tree/master/example-projects/splash
  specifically: https://github.com/cljfx/cljfx/commit/fff3034bf71a99c959e8c8aa7588bfc12cd0e33e
*/

package splash;

// Extra class indirection so JavaFX boots up correctly with uberjar.
// https://github.com/javafxports/openjdk-jfx/issues/236#issuecomment-426606561
public class Main {
  public static void main(String[] args) {
    PreloadingApplication.main(args);
  }
}
