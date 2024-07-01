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
import android.app.Activity;
import android.app.Application;
import android.os.Bundle;
import android.view.View;

import android.webkit.WebSettings;
import com.google.androidgamesdk.GameActivity;
import android.media.AudioManager;
import java.util.List;
import java.util.ArrayList;
import java.util.Arrays;
import android.webkit.WebView;

import dev.cobalt.util.Log;

/** Native activity. */
public abstract class CobaltActivity extends GameActivity {

  // A place to put args while debugging so they're used even when starting from the launcher.
  // This should always be empty in submitted code.
  private static final String[] DEBUG_ARGS = {};

  private static final String URL_ARG = "--url=";

  private long timeInNanoseconds;

  private WebView webView;

  static {
    System.loadLibrary("coat");
  }

  protected abstract StarboardBridge createStarboardBridge(String[] args, String startDeepLink);

  protected StarboardBridge getStarboardBridge() {
    return ((StarboardBridge.HostApplication) getApplication()).getStarboardBridge();
  }

  protected String[] getArgs() {
    List<String> args = new ArrayList<>(Arrays.asList(DEBUG_ARGS));
    return args.toArray(new String[0]);
  }

  @SuppressLint("SetJavaScriptEnabled")
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    // Record the application start timestamp.
    timeInNanoseconds = System.nanoTime();

    setVolumeControlStream(AudioManager.STREAM_MUSIC);

    String startDeepLink = "";

    if (getStarboardBridge() == null) {
      // Cold start - Instantiate the singleton StarboardBridge.
      StarboardBridge starboardBridge = createStarboardBridge(getArgs(), startDeepLink);
      ((StarboardBridge.HostApplication) getApplication()).setStarboardBridge(starboardBridge);
    } else {
      // Warm start - Pass the deep link to the running Starboard app.
      Log.i(TAG, "TODO..");
    }

    // *WebView setup

    // Allow debugger
    WebView.setWebContentsDebuggingEnabled(true);
    // Create a WebView instance
    webView = new WebView(this);

    // Enable JavaScript (if needed)
    WebSettings webSettings = webView.getSettings();
    webSettings.setJavaScriptEnabled(true);

    // Set a custom user-agent
    String customUserAgent = "Mozilla/5.0 (Linux armeabi-v7a; Android 12) Cobalt/26.lts.99.42-gold (unlike Gecko) v8/8.8.278.8-jit gles Starboard/15, Google_ATV_sabrina_2020/STTE.231215.005 (google, Chromecast) com.google.android.youtube.tv/6.30.300";
    webSettings.setUserAgentString(customUserAgent);

    // Set mixed content mode to allow all content to be loaded, regardless of the security origin
    webSettings.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

    // Set cache mode to allow the WebView to use the default cache behavior
    webSettings.setCacheMode(WebSettings.LOAD_DEFAULT);

    // Enable DOM storage
    webSettings.setDomStorageEnabled(true);

    // Load Kabuki
    webView.loadUrl("https://youtube.com/tv?debugjs=1");

    // super.onCreate() will cause an APP_CMD_START in native code,
    // so make sure to initialize any state beforehand that might be touched by
    // native code invocations.
    super.onCreate(savedInstanceState);

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
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            | View.SYSTEM_UI_FLAG_FULLSCREEN
    );
  }

}
