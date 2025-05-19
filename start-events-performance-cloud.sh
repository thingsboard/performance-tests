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

#CLOUD, EDGE
export SOURCE_TYPE=CLOUD
export SOURCE_URL=http://127.0.0.1:8080
export MQTT_HOST=127.0.0.1
export MQTT_PORT=1883
export TARGET_URL=http://127.0.0.1:18080
export EDGE_ID=f0d0d470-b098-11ef-bf09-3fc3f309b24e

export DEVICE_START_IDX=0
export DEVICE_END_IDX=100
export DEVICE_COUNT=100
export DEVICE_CREATE_ON_START=true
export DEVICE_DELETE_ON_COMPLETE=true

export MESSAGES_PER_SECOND=1000
export DURATION_IN_SECONDS=60
export CHECK_ATTRIBUTE_DELAY=10
export WAIT_TS_COUNT=10

mvn spring-boot:run -Dspring-boot.run.arguments="--spring.config.name=events-performance-tests"
