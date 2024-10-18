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

import android.annotation.SuppressLint;
import android.content.Intent;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.WebView;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.OnBackPressedDispatcher;
import androidx.annotation.CallSuper;
import com.google.androidgamesdk.GameActivity;
import dev.cobalt.libraries.services.accountmanager.AccountManagerModule;
import dev.cobalt.util.DisplayUtil;
import dev.cobalt.libraries.services.clientloginfo.ClientLogInfoModule;
import dev.cobalt.libraries.services.FakeSoftMicModule;
import dev.cobalt.util.Log;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Native activity.
 */
public abstract class CobaltActivity extends GameActivity {

  // A place to put args while debugging so they're used even when starting from the launcher.
  // This should always be empty in submitted code.
  private static final String[] DEBUG_ARGS = {};

  private static final String URL_ARG = "--url=";

  private long timeInNanoseconds;

  private ChrobaltWebView webView;

  @Override
  public boolean onKeyDown(int keyCode, KeyEvent event) {
    if (keyCode == KeyEvent.KEYCODE_BACK) {
      Log.d(TAG, "Special key was pressed: " + keyCode);
      webView.evaluateJavascript("injectBackKeyPress()", null);
    } else {
      Log.d(TAG, "Key was pressed: " + keyCode);
    }
    return super.onKeyDown(keyCode, event);
  }


  @SuppressLint("SetJavaScriptEnabled")
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    // Record the application start timestamp.
    timeInNanoseconds = System.nanoTime();

    // To ensure that volume controls adjust the correct stream, make this call
    // early in the app's lifecycle. This connects the volume controls to
    // STREAM_MUSIC whenever the target activity or fragment is visible.
    setVolumeControlStream(AudioManager.STREAM_MUSIC);

    String default_url = "https://youtube.com/tv?debugjs=1";

    String startDeepLink = getIntentUrlAsString(getIntent());
    Log.i(TAG, "Our starting deeplink URL is:" + startDeepLink);
    if(startDeepLink == null) {
      startDeepLink = default_url;
    }

    // super.onCreate() will cause an APP_CMD_START in native code,
    // so make sure to initialize any state beforehand that might be touched by
    // native code invocations.
    super.onCreate(savedInstanceState);

    if (getStarboardBridge() == null) {
      // Cold start - Instantiate the singleton StarboardBridge.
      StarboardBridge starboardBridge = createStarboardBridge(getArgs(), startDeepLink);
      ((StarboardBridge.HostApplication) getApplication()).setStarboardBridge(starboardBridge);
    } else {
      // Warm start - Pass the deep link to the running Starboard app.
      getStarboardBridge().handleDeepLink(startDeepLink);
    }

    CobaltService.Factory clientLogInfoFactory = new ClientLogInfoModule().provideFactory(
        getApplicationContext());
    getStarboardBridge().registerCobaltService(clientLogInfoFactory);
    CobaltService.Factory fakeSoftMicFactory = new FakeSoftMicModule().provideFactory(
        getApplicationContext());
    getStarboardBridge().registerCobaltService(fakeSoftMicFactory);
    CobaltService.Factory accountManagerFactory =
        new AccountManagerModule()
            .provideFactory(getApplicationContext(), this.getStarboardBridge().getExecutor());
    getStarboardBridge().registerCobaltService(accountManagerFactory);

    // Make the activity full-screen
    getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
        WindowManager.LayoutParams.FLAG_FULLSCREEN);

    OnBackPressedDispatcher dispatcher = getOnBackPressedDispatcher();
    dispatcher.addCallback(this, new OnBackPressedCallback(true) {
      @Override
      public void handleOnBackPressed() {
        Log.i(TAG, "Back was pressed");
        webView.goBack();
      }
    });

    // Allow debugger
    ChrobaltWebView.setWebContentsDebuggingEnabled(true);
    // Create a WebView instance
    webView = new ChrobaltWebView(this,getStarboardBridge());
    // Load Kabuki
    webView.loadUrl(startDeepLink);
    getStarboardBridge().setJavaScriptCallback(javascript -> webView.evalJavaScript(javascript));

    // Set the WebView as the main content view of the activity
    setContentView(webView);
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);

    if (hasFocus) {
      hideSystemUi();
    }
  }

  private void hideSystemUi() {
    View decorView = getWindow().getDecorView();
    decorView.setSystemUiVisibility(
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN);
  }

  protected String[] getArgs() {
    List<String> args = new ArrayList<>(Arrays.asList(DEBUG_ARGS));
    return args.toArray(new String[0]);
  }

  /**
   * Instantiates the StarboardBridge. Apps not supporting sign-in should inject an instance of
   * NoopUserAuthorizer. Apps may subclass StarboardBridge if they need to override anything.
   */
  protected abstract StarboardBridge createStarboardBridge(String[] args, String startDeepLink);

  protected StarboardBridge getStarboardBridge() {
    return ((StarboardBridge.HostApplication) getApplication()).getStarboardBridge();
  }

  @Override
  protected void onStart() {
    DisplayUtil.cacheDefaultDisplay(this);
    DisplayUtil.addDisplayListener(this);

    getStarboardBridge().onActivityStart(this);
    super.onStart();
  }

  @Override
  protected void onStop() {
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    getStarboardBridge().onActivityDestroy(this);
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

  @CallSuper
  @Override
  protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    getStarboardBridge().handleDeepLink(getIntentUrlAsString(intent));
    String url = getIntentUrlAsString(intent);
    if(url != null ) {
      webView.loadUrl(url);
    }
  }

  /**
   * Returns the URL from an Intent as a string. This may be overridden for additional processing.
   */
  protected String getIntentUrlAsString(Intent intent) {
    Uri intentUri = intent.getData();
    return (intentUri == null) ? null : intentUri.toString();
  }

  @Override
  public void onLowMemory() {
    super.onLowMemory();
  }

  public long getAppStartTimestamp() {
    return timeInNanoseconds;
  }
}
