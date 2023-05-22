#!/usr/bin/env python3
#
# Copyright 2023 The Cobalt Authors. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
"""Standard Python http.server customized for development with Cobalt."""

import os
import http.server
from functools import partial


class Handler(http.server.SimpleHTTPRequestHandler):
  """Customizes sent headers"""

  def end_headers(self):
    # Force no caching on client end
    self.send_header('Cache-Control', 'no-cache, no-store, must-revalidate')
    self.send_header('Pragma', 'no-cache')
    self.send_header('Expires', '0')
    # Blanket rule to allow all content
    self.send_header('Content-Security-Policy',
                     'default-src \'self\' \'unsafe-inline\' \'unsafe-eval\'')
    # Allow all CORS requests
    self.send_header('Access-Control-Allow-Origin:', '*')
    self.send_header('Access-Control-Allow-Methods', '*')
    super().end_headers()


if __name__ == '__main__':
  import argparse

  parser = argparse.ArgumentParser()
  parser.add_argument(
      '--bind',
      '-b',
      default='',
      metavar='ADDRESS',
      help='Specify alternate bind address '
      '[default: all interfaces]')
  parser.add_argument(
      '--directory',
      '-d',
      default=os.getcwd(),
      help='Specify alternative directory '
      '[default:current directory]')
  parser.add_argument(
      'port',
      action='store',
      default=8000,
      type=int,
      nargs='?',
      help='Specify alternate port [default: 8000]')
  args = parser.parse_args()
  handler_class = partial(Handler, directory=args.directory)
  http.server.test(HandlerClass=handler_class)
