# performance-tests
Thingsboard performance tests

Project that is able to stress test Thingsboard server with a huge number of MQTT messages published simultaneously from different devices.

To run test againt Thingsboard first modify __src/main/resources/test.properties__ file and set correct values for testing params:
  * __restUrl=http://localhost:8080__    // REST Thingsboard URL
  * __mqttUrls=tcp://localhost:1883__    // MQTT Thingsboard URL 
  * __deviceCount=20000__                // Number of devices to publish MQTT messages to
  * __publishTelemetryCount=60__         // Count of published messages
  * __publishTelemetryPause=1000__       // Puuse between sending messages
  * __username=tenant@thingsboard.org__
  * __password=tenant__
  
  
Once params are configured to run test simple type from the root folder:
```bash
mvn clean install gatling:execute
```
