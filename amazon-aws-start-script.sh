#!/bin/bash
rm /home/ubuntu/projects/performance-tests/src/main/resources/test.properties
touch /home/ubuntu/projects/performance-tests/src/main/resources/test.properties
echo "restUrl=http://locahost:8080" >> /home/ubuntu/projects/performance-tests/src/main/resources/test.properties
echo "mqttUrls=tcp://localhost:1883" >> /home/ubuntu/projects/performance-tests/src/main/resources/test.properties
echo "deviceCount=1" >> /home/ubuntu/projects/performance-tests/src/main/resources/test.properties
echo "durationMs=60000" >> /home/ubuntu/projects/performance-tests/src/main/resources/test.properties
echo "iterationIntervalMs=1000" >> /home/ubuntu/projects/performance-tests/src/main/resources/test.properties
echo "username=tenant@thingsboard.io" >> /home/ubuntu/projects/performance-tests/src/main/resources/test.properties
echo "password=tenant" >> /home/ubuntu/projects/performance-tests/src/main/resources/test.properties
echo "publishTelemetryCount=1" >> /home/ubuntu/projects/performance-tests/src/main/resources/test.properties
echo "publishTelemetryPause=100" >> /home/ubuntu/projects/performance-tests/src/main/resources/test.properties
cd /home/ubuntu/projects/performance-tests/
mvn clean install -DskipTests