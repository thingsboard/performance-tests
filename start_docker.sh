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

echo "Starting ThingsBoard Performance Test using Docker container..."
set +x
docker run -it --rm --network host --name tb-perf-test \
           --env REST_URL=http://k8s-thingsbo-tbhttplo-784e0efb43-1020620715.eu-west-1.elb.amazonaws.com:80 \
           --env MQTT_HOST=a1435f2586389421f82397b52b690867-b454cc0b7f996e3b.elb.eu-west-1.amazonaws.com \
           thingsboard/tb-ce-performance-test:3.3.3
