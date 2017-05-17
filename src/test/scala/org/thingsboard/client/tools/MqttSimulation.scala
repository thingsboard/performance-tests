/**
 * Copyright © 2016-2017 The Thingsboard Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.client.tools

import com.github.mnogu.gatling.mqtt.Predef._
import io.gatling.core.Predef._
import org.fusesource.mqtt.client.QoS
import scala.collection.JavaConverters._

import scala.concurrent.duration._

class MqttSimulation extends Simulation {

  val testParams = new TestParams()

  println(s"Running test using following configuration: " +
    s"restUrl = [${testParams.getRestApiUrl}], " +
    s"mqttUrls = [${testParams.getMqttUrls.head}], " +
    s"deviceCount = [${testParams.getDeviceCount}], " +
    s"publishTelemetryCount = [${testParams.getPublishTelemetryCount}], " +
    s"publishTelemetryPause = [${testParams.getPublishTelemetryPause}], " +
    s"username = [${testParams.getUsername}], " +
    s"password = [${testParams.getPassword}]")

  val deviceCredentialsIds: Array[String] = MqttStressTestTool.createDevices(testParams).asScala.toArray

  val mqttConf = mqtt
    .host(testParams.getMqttUrls.head)
    .userName("${deviceCredentialsId}")

  val connect = exec(mqtt("connect")
    .connect())

  val publish = repeat(testParams.getPublishTelemetryCount.toInt) {
    exec(mqtt("publish")
      .publish("v1/devices/me/telemetry", "{\"temp\":73.2}", QoS.AT_LEAST_ONCE, retain = false))
      .pause(testParams.getPublishTelemetryPause milliseconds)
  }

  val disconnect = exec(mqtt("disconnect")
    .disconnect())

  val deviceCredentialsIdsFeeder = deviceCredentialsIds.map( x => {Map("deviceCredentialsId" -> x)})

  val scn = scenario("Scenario Name")
    .feed(deviceCredentialsIdsFeeder)
    .exec(connect, publish, disconnect)

  setUp(
    scn
      .inject(rampUsers(deviceCredentialsIds.length) over (1 seconds))
  ).protocols(mqttConf)

}