#
# Copyright © 2016-2025 The Thingsboard Authors
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

FROM thingsboard/openjdk17:bookworm-slim

COPY start-tests.sh ${pkg.name}.deb /tmp/

RUN pwd \
    && chmod a+x /tmp/*.sh \
    && mv /tmp/start-tests.sh /usr/bin \
    && dpkg -i /tmp/${pkg.name}.deb \
    && rm /tmp/${pkg.name}.deb \
    && chmod 555 ${pkg.installFolder}/bin/${pkg.name}.jar

RUN systemctl --no-reload disable --now ${pkg.name}.service > /dev/null 2>&1 || :

USER ${pkg.user}

CMD ["start-tests.sh"]
