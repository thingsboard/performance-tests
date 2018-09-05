# performance-tests
Thingsboard performance tests

Project that is able to stress test Thingsboard server with a huge number of MQTT messages published simultaneously from different devices.

To run test againt Thingsboard first modify __src/main/resources/test.properties__ file and set correct values for testing params:
  * restUrl=http://localhost:8080    __// REST Thingsboard URL__
  * mqttUrls=tcp://localhost:1883    __// MQTT Thingsboard URL__
  * deviceCount=20000                __// Number of devices to publish MQTT messages to__
  * publishTelemetryCount=60         __// Count of published messages__
  * publishTelemetryPause=1000       __// Puuse between sending messages__
  * username=tenant@thingsboard.org
  * password=tenant
  
  
Once params are configured to run test simple type from the root folder:
```bash
mvn clean install gatling:execute
```
1.update gatling-mqtt project and run 'install-local-gatling-mqtt.sh'
2.udpate thingsboard and execute 'mvn clean install -DskipTests'
3.update version of thingsboard and tools.jar,data.jar in the pom.xml
