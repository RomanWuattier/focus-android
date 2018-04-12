# This Source Code Form is subject to the terms of the Mozilla Public
# License, v. 2.0. If a copy of the MPL was not distributed with this
# file, You can obtain one at http://mozilla.org/MPL/2.0/.

"""
This script talks to the taskcluster secrets service to obtain the
Nimbledroid account key and write it to the .nimbledroid_key
file in the root directory.
"""

import base64
import os
import taskcluster

# Get JSON data from taskcluster secrets service
secrets = taskcluster.Secrets({'baseUrl': 'http://taskcluster/secrets/v1'})
data = secrets.get('project/focus/nimbledroid')

with open(os.path.join(os.path.dirname(__file__), '../../.nimbledroid.key'), 'w') as file:
	file.write(base64.b64decode(data['secret']['api_key']))

print("Imported Nimbledroid upload key from secrets service")
