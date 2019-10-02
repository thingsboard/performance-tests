#
# Copyright © 2016-2018 The Thingsboard Authors
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

FROM thingsboard/openjdk8

COPY start-tests.sh ${pkg.name}.conf ${pkg.name}.deb /tmp/

RUN chmod a+x /tmp/*.sh \
    && mv /tmp/start-tests.sh /usr/bin

RUN dpkg -i /tmp/${pkg.name}.deb

RUN update-rc.d ${pkg.name} disable

RUN mv /tmp/${pkg.name}.conf ${pkg.installFolder}/conf

ENV REST_URL=localhost:9090
ENV REST_USERNAME=tenant@thingsboard.org
ENV REST_PASSWORD=tenant

ENV MQTT_HOST=localhost
ENV MQTT_PORT=1883

ENV DEVICE_API=MQTT
ENV DEVICE_START_IDX=0
ENV DEVICE_END_IDX=10
ENV DEVICE_CREATE_ON_START=true
ENV DEVICE_DELETE_ON_COMPLETE=true

ENV PUBLISH_COUNT=10
ENV PUBLISH_PAUSE=1000

CMD ["start-tests.sh"]