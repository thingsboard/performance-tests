#!/bin/bash

rm /home/ubuntu/projects/performance-tests/src/main/resources/test.properties
touch /home/ubuntu/projects/performance-tests/src/main/resources/test.properties
echo "restUrl=http://localhost:8080" >> /home/ubuntu/projects/performance-tests/src/main/resources/test.properties
echo "mqttUrls=tcp://localhost:1883" >> /home/ubuntu/projects/performance-tests/src/main/resources/test.properties
echo "deviceCount=1000" >> /home/ubuntu/projects/performance-tests/src/main/resources/test.properties
echo "username=tenant@thingsboard.org" >> /home/ubuntu/projects/performance-tests/src/main/resources/test.properties
echo "password=tenant" >> /home/ubuntu/projects/performance-tests/src/main/resources/test.properties
echo "publishTelemetryCount=10" >> /home/ubuntu/projects/performance-tests/src/main/resources/test.properties
echo "publishTelemetryPause=1000" >> /home/ubuntu/projects/performance-tests/src/main/resources/test.properties