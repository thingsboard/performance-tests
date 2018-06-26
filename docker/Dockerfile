#
# Copyright © 2016-2017 The Thingsboard Authors
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

FROM maven:3.5.0-jdk-8

RUN mkdir /root/performance-tests

COPY run_tests.sh /root/run_tests.sh
COPY data-1.4.0.jar /root/data-1.4.0.jar
COPY tools-1.4.0.jar /root/tools-1.4.0.jar
COPY tb-gatling-1.0.0.jar /root/tb-gatling-1.0.0.jar
COPY project-src.tar.gz /root/project-src.tar.gz
COPY pom.xml /root/performance-tests/pom.xml

RUN chmod +x /root/run_tests.sh

WORKDIR /root
