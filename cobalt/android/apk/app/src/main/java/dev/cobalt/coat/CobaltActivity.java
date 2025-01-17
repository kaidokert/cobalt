// Copyright 2017 The Cobalt Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package dev.cobalt.coat;

import static dev.cobalt.util.Log.TAG;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup.LayoutParams;
import android.view.ViewParent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.Toast;
import dev.cobalt.app.CobaltApplication;
import dev.cobalt.media.MediaCodecCapabilitiesLogger;
import dev.cobalt.media.VideoSurfaceView;
import dev.cobalt.util.DisplayUtil;
import dev.cobalt.util.Log;
import dev.cobalt.util.UsedByNative;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
// import dev.cobalt.media.AudioOutputManager;
import org.chromium.base.CommandLine;
import org.chromium.base.MemoryPressureListener;
import org.chromium.base.library_loader.LibraryLoader;
import org.chromium.base.library_loader.LibraryProcessType;
import org.chromium.content_public.browser.BrowserStartupController;
import org.chromium.content_public.browser.DeviceUtils;
import org.chromium.content_public.browser.WebContents;
import org.chromium.content_shell.Shell;
import org.chromium.content_shell.ShellManager;
import org.chromium.ui.base.ActivityWindowAndroid;
import org.chromium.ui.base.IntentRequestTracker;

/** Native activity that has the required JNI methods called by the Starboard implementation. */
public abstract class CobaltActivity extends Activity {

  // A place to put args while debugging so they're used even when starting from the launcher.
  // This should always be empty in submitted code.
  private static final String[] DEBUG_ARGS = {};

  private static final String URL_ARG = "--url=";
  private static final java.lang.String META_DATA_APP_URL = "cobalt.APP_URL";

  private static final String SPLASH_URL_ARG = "--fallback_splash_screen_url=";
  private static final String SPLASH_TOPICS_ARG = "--fallback_splash_screen_topics=";
  private static final java.lang.String META_DATA_SPLASH_URL = "cobalt.SPLASH_URL";
  private static final java.lang.String META_DATA_SPLASH_TOPICS = "cobalt.SPLASH_TOPIC";

  private static final String FORCE_MIGRATION_FOR_STORAGE_PARTITIONING =
      "--force_migration_for_storage_partitioning";
  private static final String META_FORCE_MIGRATION_FOR_STORAGE_PARTITIONING =
      "cobalt.force_migration_for_storage_partitioning";

  private static final String EVERGREEN_LITE = "--evergreen_lite";
  private static final java.lang.String META_DATA_EVERGREEN_LITE = "cobalt.EVERGREEN_LITE";

  private static final String ACTIVE_SHELL_URL_KEY = "activeUrl";
  public static final String COMMAND_LINE_ARGS_KEY = "commandLineArgs";

  // Native switch - shell_switches::kRunWebTests
  private static final String RUN_WEB_TESTS_SWITCH = "run-web-tests";

  private static final Pattern URL_PARAM_PATTERN = Pattern.compile("^[a-zA-Z0-9_=]*$");

  private VideoSurfaceView videoSurfaceView;

  private boolean forceCreateNewVideoSurfaceView = false;

  private long timeInNanoseconds;

  private ShellManager mShellManager;
  private ActivityWindowAndroid mWindowAndroid;
  private Intent mLastSentIntent;
  private String mStartupUrl;
  private IntentRequestTracker mIntentRequestTracker;

  // Initially copied from ContentShellActiviy.java
  protected void createContent(final Bundle savedInstanceState) {
    Log.e(TAG, "YOLO: createContent");
    // Initializing the command line must occur before loading the library.
    if (!CommandLine.isInitialized()) {
      ((CobaltApplication) getApplication()).initCommandLine();

      // Note that appendSwitchesAndArguments excludes cobaltCommandLineParams[0]
      // as the program name, and all other arguments SHOULD start with '--'.
      String[] cobaltCommandLineParams =
          new String[] {
            "",
            // disable first run experience
            "--disable-fre",
            // disable user prompts in the first run
            "--no-first-run",
            // run Cobalt as a single process
            "--single-process",
            // enable Blink to work in overlay video mode
            "--force-video-overlays",
            // remove below if Cobalt rebase to m120+
            "--user-level-memory-pressure-signal-params"
          };
      CommandLine.getInstance().appendSwitchesAndArguments(cobaltCommandLineParams);

      String[] commandLineParams = getCommandLineParamsFromIntent(getIntent());
      if (commandLineParams != null) {
        CommandLine.getInstance().appendSwitchesAndArguments(commandLineParams);
      }
    }

    DeviceUtils.addDeviceSpecificUserAgentSwitch();

    // This loads and initializes JNI
    LibraryLoader.getInstance().ensureInitialized();

    Log.e(TAG, "We are done with JNI init, lets make Starboard Bridge");
    // After this we create StarboardBridge
    // TODO(b/374147993): how to handle deeplink in Chrobalt?
    String startDeepLink = getIntentUrlAsString(getIntent());
    if (getStarboardBridge() == null) {
      // Cold start - Instantiate the singleton StarboardBridge.
      StarboardBridge starboardBridge = createStarboardBridge(getArgs(), startDeepLink);
      ((StarboardBridge.HostApplication) getApplication()).setStarboardBridge(starboardBridge);
    } else {
      // Warm start - Pass the deep link to the running Starboard app.
      getStarboardBridge().handleDeepLink(startDeepLink);
    }

    Log.e(TAG, "We are done with JNI init, Starboard bridge also done!");

    setContentView(R.layout.content_shell_activity);
    mShellManager = findViewById(R.id.shell_container);
    final boolean listenToActivityState = true;
    mIntentRequestTracker = IntentRequestTracker.createFromActivity(this);
    mWindowAndroid = new ActivityWindowAndroid(this, listenToActivityState, mIntentRequestTracker);
    mIntentRequestTracker.restoreInstanceState(savedInstanceState);
    mShellManager.setWindow(mWindowAndroid);
    // Set up the animation placeholder to be the SurfaceView. This disables the
    // SurfaceView's 'hole' clipping during animations that are notified to the window.
    mWindowAndroid.setAnimationPlaceholderView(
        mShellManager.getContentViewRenderView().getSurfaceView());

    // TODO(cobalt, b/376148547): set Chrobalt initial url and remove this function.
    if (mStartupUrl.isEmpty()) {
      mStartupUrl = getUrlFromIntent(getIntent());
    }
    if (!TextUtils.isEmpty(mStartupUrl)) {
      mShellManager.setStartupUrl(Shell.sanitizeUrl(mStartupUrl));
    }

    //      if (CommandLine.getInstance().hasSwitch(RUN_WEB_TESTS_SWITCH)) {
    Log.e(TAG, "YOLO: Launch Cobalt as single process sync thing.");
    BrowserStartupController.getInstance()
        .startBrowserProcessesSync(LibraryProcessType.PROCESS_BROWSER, true, false);
    /*
          } else {
              BrowserStartupController.getInstance().startBrowserProcessesAsync(
                      LibraryProcessType.PROCESS_BROWSER, false, true,
                      new BrowserStartupController.StartupCallback() {
                          @Override
                          public void onSuccess() {
                              finishInitialization(savedInstanceState);
                          }

                          @Override
                          public void onFailure() {
                              initializationFailed();
                          }
                      });
          }
    */
  }

  // Initially copied from ContentShellActiviy.java
  private void finishInitialization(Bundle savedInstanceState) {
    String shellUrl;
    if (!TextUtils.isEmpty(mStartupUrl)) {
      shellUrl = mStartupUrl;
    } else {
      shellUrl = ShellManager.DEFAULT_SHELL_URL;
    }

    if (savedInstanceState != null && savedInstanceState.containsKey(ACTIVE_SHELL_URL_KEY)) {
      shellUrl = savedInstanceState.getString(ACTIVE_SHELL_URL_KEY);
    }
    // Set to overlay video mode.
    mShellManager.getContentViewRenderView().setOverlayVideoMode(true);
    mShellManager.launchShell(shellUrl);

    toggleFullscreenMode(true);
  }

  // Initially copied from ContentShellActiviy.java
  private void initializationFailed() {
    Log.e(TAG, "ContentView initialization failed.");
    Toast.makeText(
            CobaltActivity.this, R.string.browser_process_initialization_failed, Toast.LENGTH_SHORT)
        .show();
    finish();
  }

  // Initially copied from ContentShellActiviy.java
  @Override
  protected void onSaveInstanceState(Bundle outState) {
    super.onSaveInstanceState(outState);
    WebContents webContents = getActiveWebContents();
    if (webContents != null) {
      // TODO(yfriedman): crbug/783819 - This should use GURL serialize/deserialize.
      outState.putString(ACTIVE_SHELL_URL_KEY, webContents.getLastCommittedUrl().getSpec());
    }

    mIntentRequestTracker.saveInstanceState(outState);
  }

  // Initially copied from ContentShellActiviy.java
  @Override
  public boolean onKeyUp(int keyCode, KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_BACK) {
      WebContents webContents = getActiveWebContents();
      if (webContents != null && webContents.getNavigationController().canGoBack()) {
        webContents.getNavigationController().goBack();
        return true;
      }
    }

    return super.onKeyUp(keyCode, event);
  }

  // Initially copied from ContentShellActiviy.java
  protected void shellHandleIntent(Intent intent) {
    if (getCommandLineParamsFromIntent(intent) != null) {
      Log.i(TAG, "Ignoring command line params: can only be set when creating the activity.");
    }

    if (MemoryPressureListener.handleDebugIntent(this, intent.getAction())) return;

    String url = getUrlFromIntent(intent);
    if (!TextUtils.isEmpty(url)) {
      Shell activeView = getActiveShell();
      if (activeView != null) {
        activeView.loadUrl(url);
      }
    }
  }

  // TODO(cobalt, b/376148547): set Chrobalt initial url and remove this function.
  protected void setStartupUrl(String url) {
    mStartupUrl = url;
  }

  protected void toggleFullscreenMode(boolean enterFullscreen) {
    LinearLayout toolBar = (LinearLayout) findViewById(R.id.toolbar);
    toolBar.setVisibility(enterFullscreen ? View.GONE : View.VISIBLE);
  }

  // Initially copied from ContentShellActiviy.java
  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    mIntentRequestTracker.onActivityResult(requestCode, resultCode, data);
  }

  // Initially copied from ContentShellActiviy.java
  @Override
  public void startActivity(Intent i) {
    mLastSentIntent = i;
    super.startActivity(i);
  }

  public Intent getLastSentIntent() {
    return mLastSentIntent;
  }

  private static String getUrlFromIntent(Intent intent) {
    return intent != null ? intent.getDataString() : null;
  }

  private static String[] getCommandLineParamsFromIntent(Intent intent) {
    return intent != null ? intent.getStringArrayExtra(COMMAND_LINE_ARGS_KEY) : null;
  }

  /**
   * @return The {@link ShellManager} configured for the activity or null if it has not been created
   *     yet.
   */
  public ShellManager getShellManager() {
    return mShellManager;
  }

  /**
   * @return The currently visible {@link Shell} or null if one is not showing.
   */
  public Shell getActiveShell() {
    return mShellManager != null ? mShellManager.getActiveShell() : null;
  }

  /**
   * @return The {@link WebContents} owned by the currently visible {@link Shell} or null if one is
   *     not showing.
   */
  public WebContents getActiveWebContents() {
    Shell shell = getActiveShell();
    return shell != null ? shell.getWebContents() : null;
  }

  // TODO(b/375442742): re-enable native code.
  // private static native void nativeLowMemoryEvent();

  // TODO(cobalt): make WebContent accessible in CobaltActivity or StarboardBridge.
  // protected View mContentView = null;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    Log.e(TAG, "YOLO: Activity onCreate");

    // Record the application start timestamp.
    timeInNanoseconds = System.nanoTime();

    setStartupUrl("https://www.youtube.com/tv");
    super.onCreate(savedInstanceState);
    createContent(savedInstanceState);

    // To ensure that volume controls adjust the correct stream, make this call
    // early in the app's lifecycle. This connects the volume controls to
    // STREAM_MUSIC whenever the target activity or fragment is visible.
    setVolumeControlStream(AudioManager.STREAM_MUSIC);

    videoSurfaceView = new VideoSurfaceView(this);
    addContentView(
        videoSurfaceView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
  }

  /**
   * Instantiates the StarboardBridge. Apps not supporting sign-in should inject an instance of
   * NoopUserAuthorizer. Apps may subclass StarboardBridge if they need to override anything.
   */
  protected abstract StarboardBridge createStarboardBridge(String[] args, String startDeepLink);

  @UsedByNative
  protected StarboardBridge getStarboardBridge() {
    return ((StarboardBridge.HostApplication) getApplication()).getStarboardBridge();
  }

  @Override
  protected void onStart() {
    if (!isReleaseBuild()) {
      // TODO(cobalt): re-enable Cobalt AudioOutputManager.
      // getStarboardBridge().getAudioOutputManager().dumpAllOutputDevices();
      MediaCodecCapabilitiesLogger.dumpAllDecoders();
    }
    if (forceCreateNewVideoSurfaceView) {
      Log.w(TAG, "Force to create a new video surface.");
      createNewSurfaceView();
    }

    DisplayUtil.cacheDefaultDisplay(this);
    DisplayUtil.addDisplayListener(this);
    // AudioOutputManager.addAudioDeviceListener(this);

    getStarboardBridge().onActivityStart(this);

    WebContents webContents = getActiveWebContents();
    if (webContents != null) webContents.onShow();
    super.onStart();
  }

  @Override
  protected void onStop() {
    getStarboardBridge().onActivityStop(this);
    super.onStop();

    if (VideoSurfaceView.getCurrentSurface() != null) {
      forceCreateNewVideoSurfaceView = true;
    }

    // Set the SurfaceView to fullscreen.
    View rootView = getWindow().getDecorView();
    setVideoSurfaceBounds(0, 0, rootView.getWidth(), rootView.getHeight());
  }

  @Override
  protected void onDestroy() {
    if (mShellManager != null) mShellManager.destroy();
    mWindowAndroid.destroy();
    super.onDestroy();
    getStarboardBridge().onActivityDestroy(this);
  }

  @Override
  public boolean onSearchRequested() {
    return getStarboardBridge().onSearchRequested();
  }

  /** Returns true if the argument list contains an arg starting with argName. */
  private static boolean hasArg(List<String> args, String argName) {
    for (String arg : args) {
      if (arg.startsWith(argName)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Get argv/argc style args, if any from intent extras. Returns empty array if there are none
   *
   * <p>To use, invoke application via, eg, adb shell am start --esa args arg1,arg2 \
   * dev.cobalt.coat/dev.cobalt.app.MainActivity
   */
  protected String[] getArgs() {
    Bundle extras = getIntent().getExtras();
    CharSequence[] argsExtra =
        (extras == null || isReleaseBuild()) ? null : extras.getCharSequenceArray("args");

    List<String> args = new ArrayList<>(Arrays.asList(DEBUG_ARGS));
    if (argsExtra != null) {
      for (int i = 0; i < argsExtra.length; i++) {
        // Replace escaped commas with commas. In order to have a comma in the arg string, it has
        // to be escaped when forming the Intent with "am start --esa". However, "am" doesn't remove
        // the escape after splitting on unescaped commas, so it's still in the string we get.
        args.add(argsExtra[i].toString().replace("\\,", ","));
      }
    }

    // If the URL arg isn't specified, get it from AndroidManifest.xml.
    boolean hasUrlArg = hasArg(args, URL_ARG);
    // If the splash screen url arg isn't specified, get it from AndroidManifest.xml.
    boolean hasSplashUrlArg = hasArg(args, SPLASH_URL_ARG);
    // If the splash screen topics arg isn't specified, get it from AndroidManifest.xml.
    boolean hasSplashTopicsArg = hasArg(args, SPLASH_TOPICS_ARG);
    // If the Evergreen-Lite arg isn't specified, get it from AndroidManifest.xml.
    boolean hasEvergreenLiteArg = hasArg(args, EVERGREEN_LITE);
    if (!hasUrlArg || !hasSplashUrlArg || !hasSplashTopicsArg || !hasEvergreenLiteArg) {
      try {
        ActivityInfo ai =
            getPackageManager()
                .getActivityInfo(getIntent().getComponent(), PackageManager.GET_META_DATA);
        if (ai.metaData != null) {
          if (!hasUrlArg) {
            String url = ai.metaData.getString(META_DATA_APP_URL);
            if (url != null) {
              args.add(URL_ARG + url);
            }
          }
          if (!hasSplashUrlArg) {
            String splashUrl = ai.metaData.getString(META_DATA_SPLASH_URL);
            if (splashUrl != null) {
              args.add(SPLASH_URL_ARG + splashUrl);
            }
          }
          if (!hasSplashTopicsArg) {
            String splashTopics = ai.metaData.getString(META_DATA_SPLASH_TOPICS);
            if (splashTopics != null) {
              args.add(SPLASH_TOPICS_ARG + splashTopics);
            }
          }
          if (!hasEvergreenLiteArg && ai.metaData.getBoolean(META_DATA_EVERGREEN_LITE)) {
            args.add(EVERGREEN_LITE);
          }
          if (ai.metaData.getBoolean(META_FORCE_MIGRATION_FOR_STORAGE_PARTITIONING)) {
            args.add(FORCE_MIGRATION_FOR_STORAGE_PARTITIONING);
          }
        }
      } catch (NameNotFoundException e) {
        throw new RuntimeException("Error getting activity info", e);
      }
    }

    CharSequence[] urlParams = (extras == null) ? null : extras.getCharSequenceArray("url_params");
    if (urlParams != null) {
      appendUrlParamsToUrl(args, urlParams);
    }

    addCustomProxyArgs(args);
    return args.toArray(new String[0]);
  }

  private void appendUrlParamsToUrl(List<String> args, CharSequence[] urlParams) {
    int idx = -1;
    for (int i = 0; i < args.size(); i++) {
      if (args.get(i).startsWith(URL_ARG)) {
        idx = i;
        break;
      }
    }

    if (idx >= 0) {
      StringBuilder urlBuilder = new StringBuilder();
      urlBuilder.append(args.get(idx));
      // append & if ? is already in the url, otherwise append ?
      if (urlBuilder.indexOf("?") > 0) {
        urlBuilder.append("&");
      } else {
        urlBuilder.append("?");
      }

      for (int j = 0; j < urlParams.length; j++) {
        // sanitize the input before append to the url.
        String paramKeyValuePair = urlParams[j].toString();
        if (URL_PARAM_PATTERN.matcher(paramKeyValuePair).matches()) {
          urlBuilder.append(paramKeyValuePair);
          urlBuilder.append('&');
        }
      }

      urlBuilder.deleteCharAt(urlBuilder.length() - 1);
      args.set(idx, urlBuilder.toString());
    }
  }

  private static void addCustomProxyArgs(List<String> args) {
    Pair<String, String> config = detectSystemProxyConfig();

    if (config.first == null || config.second == null) {
      return;
    }

    try {
      int port = Integer.parseInt(config.second);
      if (port <= 0 || port > 0xFFFF) {
        return;
      }

      String customProxy =
          String.format(Locale.US, "--proxy=\"http=http://%s:%d\"", config.first, port);
      Log.i(TAG, "addCustomProxyArgs: " + customProxy);
      args.add(customProxy);
    } catch (NumberFormatException e) {
      Log.w(TAG, "http.proxyPort: %s is not valid number", config.second, e);
    }
  }

  private static Pair<String, String> detectSystemProxyConfig() {
    String httpHost = System.getProperty("http.proxyHost", null);
    String httpPort = System.getProperty("http.proxyPort", null);
    return new Pair<String, String>(httpHost, httpPort);
  }

  protected boolean isReleaseBuild() {
    return StarboardBridge.isReleaseBuild();
  }

  @Override
  protected void onNewIntent(Intent intent) {
    shellHandleIntent(intent);
    getStarboardBridge().handleDeepLink(getIntentUrlAsString(intent));
  }

  /**
   * Returns the URL from an Intent as a string. This may be overridden for additional processing.
   */
  protected String getIntentUrlAsString(Intent intent) {
    Uri intentUri = intent.getData();
    return (intentUri == null) ? null : intentUri.toString();
  }

  // TODO(cobalt): re-eanble microphone permission request at startup or remove code.
  // @SuppressLint("MissingSuperCall")
  // @Override
  // public void onRequestPermissionsResult(
  //     int requestCode, String[] permissions, int[] grantResults) {
  //   getStarboardBridge().onRequestPermissionsResult(requestCode, permissions, grantResults);
  // }

  public void resetVideoSurface() {
    runOnUiThread(
        new Runnable() {
          @Override
          public void run() {
            createNewSurfaceView();
          }
        });
  }

  public void setVideoSurfaceBounds(final int x, final int y, final int width, final int height) {
    if (width == 0 || height == 0) {
      // The SurfaceView should be covered by our UI layer in this case.
      return;
    }
    runOnUiThread(
        new Runnable() {
          @Override
          public void run() {
            LayoutParams layoutParams = videoSurfaceView.getLayoutParams();
            // Since videoSurfaceView is added directly to the Activity's content view, which is a
            // FrameLayout, we expect its layout params to become FrameLayout.LayoutParams.
            if (layoutParams instanceof FrameLayout.LayoutParams) {
              ((FrameLayout.LayoutParams) layoutParams).setMargins(x, y, x + width, y + height);
            } else {
              Log.w(
                  TAG,
                  "Unexpected video surface layout params class "
                      + layoutParams.getClass().getName());
            }
            layoutParams.width = width;
            layoutParams.height = height;
            // Even though as a NativeActivity we're not using the Android UI framework, by setting
            // the  layout params it will force a layout to be requested. That will cause the
            // SurfaceView to position its underlying Surface to match the screen coordinates of
            // where the view would be in a UI layout and to set the surface transform matrix to
            // match the view's size.
            videoSurfaceView.setLayoutParams(layoutParams);
          }
        });
  }

  private void createNewSurfaceView() {
    ViewParent parent = videoSurfaceView.getParent();
    if (parent instanceof FrameLayout) {
      FrameLayout frameLayout = (FrameLayout) parent;
      int index = frameLayout.indexOfChild(videoSurfaceView);
      frameLayout.removeView(videoSurfaceView);
      videoSurfaceView = new VideoSurfaceView(this);
      frameLayout.addView(
          videoSurfaceView,
          index,
          new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
    } else {
      Log.w(TAG, "Unexpected surface view parent class " + parent.getClass().getName());
    }
  }

  @Override
  public void onLowMemory() {
    super.onLowMemory();
    // TODO(cobalt): re-enable native low memory event or remove code if unnecessary.
    // nativeLowMemoryEvent();
  }

  public long getAppStartTimestamp() {
    return timeInNanoseconds;
  }
}
