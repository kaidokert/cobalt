// Copyright 2019 The Cobalt Authors. All Rights Reserved.
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

import dev.cobalt.util.Log;

/** Abstract class that provides an interface for Cobalt to interact with a platform service. */
public abstract class CobaltService {
  // Indicate is the service opened, and be able to send data to client
  protected boolean opened = true;

  /** Interface that returns an object that extends CobaltService. */
  public interface Factory {
    /** Create the service. */
    public CobaltService createCobaltService(long nativeService);

    /** Get the name of the service. */
    public String getServiceName();
  }

  // Lifecycle
  /** Prepare service for start or resume. */
  public abstract void beforeStartOrResume();

  /** Prepare service for suspend. */
  public abstract void beforeSuspend();

  /** Prepare service for stop. */
  public abstract void afterStopped();

  // Service API
  /** Response to client from calls to receiveFromClient(). */
  @SuppressWarnings("unused")
  public static class ResponseToClient {
    /** Indicate if the service was unable to receive data because it is in an invalid state. */
    @SuppressWarnings("unused")
    public boolean invalidState;

    /** The synchronous response data from the service. */
    @SuppressWarnings("unused")
    public byte[] data;
  }

  /** Receive data from client of the service. */
  @SuppressWarnings("unused")
  public abstract ResponseToClient receiveFromClient(byte[] data);

  /**
   * Close the service.
   *
   * <p>Once this function returns, it is invalid to call sendToClient for the nativeService, so
   * synchronization must be used to protect against this.
   */
  @SuppressWarnings("unused")
  public void onClose() {
    synchronized (this) {
      opened = false;
      close();
    }
  }

  public abstract void close();

  /**
   * Send data from the service to the client.
   *
   * <p>This may be called from a separate thread, do not call nativeSendToClient() once onClose()
   * is processed.
   */
  protected void sendToClient(long nativeService, byte[] data) {
    synchronized (this) {
      if (!opened) {
        Log.w(
            TAG,
            "Platform service did not send data to client, because client already closed the"
                + " platform service.");
        return;
      } else {
        Log.w(TAG, "Hey i got the service " + nativeService);
        Log.w(TAG, "But i didn't bother to send it yet");
      }
    }
  }
}
