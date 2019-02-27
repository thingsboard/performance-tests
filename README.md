# performance-tests
Thingsboard performance tests

Project that is able to stress test Thingsboard server with a huge number of MQTT messages published simultaneously from different devices.

## Prerequisites

- [Install Docker CE](https://docs.docker.com/engine/installation/)

## Running

To run test against ThingsBoard first create plain text file to set up test configuration (in our example configuration file name is *.env*):
```bash
touch .env
```

Edit this *.env* file:
```bash
nano .env
```

and put next content into the text file (modify it according to your test goals):
```bash
REST_URL=http://IP_ADDRESS_OF_TB_INSTANCE:9090
# IP_ADDRESS_OF_TB_INSTANCE is your local IP address if you run ThingsBoard on your dev machine in docker
# Port should be modified as well if needed 
REST_USERNAME=tenant@thingsboard.org
REST_PASSWORD=tenant

MQTT_HOST=IP_ADDRESS_OF_TB_INSTANCE
# IP_ADDRESS_OF_TB_INSTANCE is your local IP address if you run ThingsBoard on your dev machine in docker
MQTT_PORT=1883

DEVICE_API=MQTT
DEVICE_START_IDX=0
DEVICE_END_IDX=10
DEVICE_CREATE_ON_START=true
DEVICE_DELETE_ON_COMPLETE=true

PUBLISH_COUNT=10
PUBLISH_PAUSE=1000
```

Where: 
    
- `REST_URL`                     - Rest URL of the TB instance
- `REST_USERNAME`                - Login of the user 
- `REST_PASSWORD`                - Password of the user
- `MQTT_HOST`                    - URL of the ThingsBoard MQTT broker
- `MQTT_PORT`                    - Port of the ThingsBoard MQTT broker
- `DEVICE_API`                   - Use MQTT or HTTP Device API for send messages
- `DEVICE_START_IDX`             - First index of the device that is going to be used in the test. Token of the device is going to be index of this device during test
- `DEVICE_END_IDX`               - Last index of the device that is going to be used in  the test
- `DEVICE_CREATE_ON_START`       - Create devices before test 
- `DEVICE_DELETE_ON_COMPLETE`    - Delete devices after test, there were created on start of the test
- `PUBLISH_COUNT`                - Number of the messages to be published for a single simulated device
- `PUBLISH_PAUSE`                - Pause between messages for a single simulated device in milliseconds
- `MQTT_SSL_ENABLED`             - Enable/disable ssl for MQTT
- `MQTT_SSL_KEY_STORE`           - MQTT key store file location
- `MQTT_SSL_KEY_STORE_PASSWORD`  - MQTT key store file password

Once params are configured to run test simple type from the folder where configuration file is located:
```bash
docker run -it --env-file .env --name tb-perf-test thingsboard/tb-performance-test
```
