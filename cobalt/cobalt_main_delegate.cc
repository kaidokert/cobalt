// Copyright 2024 The Cobalt Authors. All Rights Reserved.
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

#include "cobalt/cobalt_main_delegate.h"
#include "cobalt/cobalt_content_browser_client.h"

#include <android/log.h>

namespace cobalt {

CobaltMainDelegate::CobaltMainDelegate(bool is_content_browsertests)
    : content::ShellMainDelegate(is_content_browsertests) {
  __android_log_print(ANDROID_LOG_ERROR, "yolo", "%s",
                      "CobaltMainDelegate::CobaltMainDelegate");
}

CobaltMainDelegate::~CobaltMainDelegate() {}

content::ContentBrowserClient*
CobaltMainDelegate::CreateContentBrowserClient() {
  __android_log_print(ANDROID_LOG_ERROR, "yolo", "%s",
                      "CobaltMainDelegate::CreateContentBrowserClient");
  browser_client_ = std::make_unique<CobaltContentBrowserClient>();
  __android_log_print(ANDROID_LOG_ERROR, "yolo", "%s", "Returning the client");
  return browser_client_.get();
}

}  // namespace cobalt
