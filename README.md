# performance-tests
ThingsBoard performance tests

Project that is able to stress test ThingsBoard server with a huge number of MQTT messages published simultaneously from different devices.

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
REST_URL=http://IP_ADDRESS_OF_TB_INSTANCE:8080
# IP_ADDRESS_OF_TB_INSTANCE is your local IP address if you run ThingsBoard on your dev machine in docker
# Port should be modified as well if needed 
REST_USERNAME=tenant@thingsboard.org
REST_PASSWORD=tenant

MQTT_HOST=IP_ADDRESS_OF_TB_INSTANCE
# IP_ADDRESS_OF_TB_INSTANCE is your local IP address if you run ThingsBoard on your dev machine in docker
MQTT_PORT=1883

MQTT_SSL_ENABLED=false
MQTT_SSL_KEY_STORE=mqttclient.jks
MQTT_SSL_KEY_STORE_PASSWORD=

# Test API to use - device or gateway. In case device data is send directly to devices, in case gateway - over MQTT gateway API
TEST_API=gateway

# Device API to use - MQTT or HTTP. HTTP applicable only in case TEST_API=device
DEVICE_API=MQTT

DEVICE_START_IDX=0
DEVICE_END_IDX=10
DEVICE_CREATE_ON_START=true
DEVICE_DELETE_ON_COMPLETE=true

GATEWAY_START_IDX=0
GATEWAY_END_IDX=3
GATEWAY_CREATE_ON_START=true
GATEWAY_DELETE_ON_COMPLETE=true

WARMUP_ENABLED=false

# Type of the payload to send: DEFAULT, SMART_TRACKER, SMART_METER
# RANDOM - TODO: add description
# SMART_TRACKER - sample payload: {"latitude": 42.222222, "longitude": 73.333333, "speed": 55.5, "fuel": 92, "batteryLevel": 81}
# SMART_METER - sample payload: {"pulseCounter": 1234567, "leakage": false, "batteryLevel": 81}
TEST_PAYLOAD_TYPE=SMART_TRACKER

TEST_ENABLED=true

# true - send data to devices by device ids, false - select random devices from the list  
TEST_SEQUENTIAL=true

MESSAGES_PER_SECOND=1000
DURATION_IN_SECONDS=10

UPDATE_ROOT_RULE_CHAIN=false
REVERT_ROOT_RULE_CHAIN=false
RULE_CHAIN_NAME=root_rule_chain_ce.json

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
- `MESSAGES_PER_SECOND`          - Number of the messages to be published per second to ThingsBoard
- `DURATION_IN_SECONDS`          - Number of seconds run of the test
- `MQTT_SSL_ENABLED`             - Enable/disable ssl for MQTT
- `MQTT_SSL_KEY_STORE`           - MQTT key store file location
- `MQTT_SSL_KEY_STORE_PASSWORD`  - MQTT key store file password

Once params are configured to run test simple type from the folder where configuration file is located:
```bash
docker run -it --env-file .env --name tb-perf-test thingsboard/tb-ce-performance-test:3.2.0
```
