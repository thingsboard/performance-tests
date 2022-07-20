#!/bin/bash
#
# Copyright © 2016-2022 The Thingsboard Authors
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
#

echo "Starting ThingsBoard Performance Test..."

export REST_URL=http://127.0.0.1:8080
export MQTT_HOST=127.0.0.1
export DEVICE_END_IDX=1111
export MESSAGES_PER_SECOND=1000
export ALARMS_PER_SECOND=1
export DURATION_IN_SECONDS=86400
export DEVICE_CREATE_ON_START=true

mvn spring-boot:run
