#!/bin/sh
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

#/home/nick/Igor_project/Thingsboard_Perfrmance_test/performance-tests/src/main/resources/credentials/shell/lwM2M_credentials.sh -p LwX509 -s 0 -f 2000 -a client_alias_ -e client_self_signed_ -b bootstrap -d server -j serverKeyStore.jks -k clientKeyStore.jks -c client_ks_password -w server_ks_password

#p) CLIENT_CN=$CLIENT_PREFIX00000000
#s) client_start=0
#f) client_finish=1
#a) CLIENT_ALIAS=CLIENT_ALIAS_PREFIX_00000000
#e) CLIENT_SELF_ALIAS=CLIENT_SELF_ALIAS_PREFIX_00000000
#b) BOOTSTRAP_ALIAS=bootstrap
#d) SERVER_ALIAS=server
#j) SERVER_STORE=serverKeyStore.jks
#k) CLIENT_STORE=clientKeyStore.jks
#c) CLIENT_STORE_PWD=client_ks_password
#w) SERVER_STORE_PWD=server_ks_password
#l) ROOT_KEY_ALIAS=root_key_alias

while getopts p:s:f:a:e:b:d:j:k:c:w:l: flag; do
  case "${flag}" in
  p) client_pref=${OPTARG} ;;
  s) client_start=${OPTARG} ;;
  f) client_finish=${OPTARG} ;;
  a) client_alias_pref=${OPTARG} ;;
  e) client_self_alias_pref=${OPTARG} ;;
  b) bootstrap_alias=${OPTARG} ;;
  d) server_alias=${OPTARG} ;;
  j) key_store_server_file=${OPTARG} ;;
  k) key_store_client_file=${OPTARG} ;;
  c) client_key_store_pwd=${OPTARG} ;;
  w) server_key_store_pwd=${OPTARG} ;;
  w) root_key_alias=${OPTARG} ;;
  esac
done

# cd to dir of script
script_dir=$(dirname $0)
echo "script_dir: $script_dir"
cd $script_dir
# source the properties:
. ./lwM2M_keygen.properties

if [ -n "$client_pref" ]; then
  CLIENT_PREFIX=$client_pref
fi

if [ -z "$client_start" ]; then
  client_start=0
fi

if [ -z "$client_finish" ]; then
  client_finish=1
fi

if [ -n "$client_alias_pref" ]; then
  CLIENT_ALIAS_PREFIX=$client_alias_pref
fi

if [ -n "$client_self_alias_pref" ]; then
  CLIENT_SELF_ALIAS_PREFIX=$client_self_alias_pref
fi

if [ -n "$bootstrap_alias" ]; then
  BOOTSTRAP_ALIAS=$bootstrap_alias
fi

if [ -n "$server_alias" ]; then
  SERVER_ALIAS=$server_alias
fi

if [ -n "$key_store_server_file" ]; then
  SERVER_STORE=$key_store_server_file
fi

if [ -n "$key_store_client_file" ]; then
  CLIENT_STORE=$key_store_client_file
fi

if [ -n "$client_key_store_pwd" ]; then
  CLIENT_STORE_PWD=$client_key_store_pwd
fi

if [ -n "$server_key_store_pwd" ]; then
  SERVER_STORE_PWD=$server_key_store_pwd
fi

if [ -n "$root_key_alias" ]; then
  ROOT_KEY_ALIAS=$root_key_alias
fi

CLIENT_NUMBER=$client_start

echo "==Start=="
echo "CLIENT_PREFIX: $CLIENT_PREFIX"
echo "client_start: $client_start"
echo "client_finish: $client_finish"
echo "CLIENT_ALIAS_PREFIX: $CLIENT_ALIAS_PREFIX"
echo "CLIENT_SELF_ALIAS_PREFIX: $CLIENT_SELF_ALIAS_PREFIX"
echo "BOOTSTRAP_ALIAS: $BOOTSTRAP_ALIAS"
echo "SERVER_ALIAS: $SERVER_ALIAS"
echo "SERVER_STORE: $SERVER_STORE"
echo "CLIENT_STORE: $CLIENT_STORE"
echo "CLIENT_STORE_PWD: $CLIENT_STORE_PWD"
echo "SERVER_STORE_PWD: $SERVER_STORE_PWD"
echo "CLIENT_NUMBER: $CLIENT_NUMBER"
echo "ROOT_KEY_ALIAS: $ROOT_KEY_ALIAS"

end_point() {
  echo "$CLIENT_PREFIX$(printf "%08d" $CLIENT_NUMBER)"
}

client_alias_point() {
  echo "$CLIENT_ALIAS_PREFIX$(printf "%08d" $CLIENT_NUMBER)"
}

client_self_alias_point() {
  echo "$CLIENT_SELF_ALIAS_PREFIX$(printf "%08d" $CLIENT_NUMBER)"
}

# Generation of the keystore.
echo "${H0}====START========${RESET}"
echo "${H1}Server Keystore : ${RESET}"
echo "${H1}==================${RESET}"
echo "${H2}Creating the trusted root CA key and certificate...${RESET}"
# -keysize
#    1024 (when using -genkeypair)
keytool \
  -genkeypair \
  -alias $ROOT_KEY_ALIAS \
  -keyalg EC \
  -dname "CN=$ROOT_CN, OU=$ORGANIZATIONAL_UNIT, O=$ORGANIZATION, L=$CITY, ST=$STATE_OR_PROVINCE, C=$TWO_LETTER_COUNTRY_CODE" \
  -validity $VALIDITY \
  -storetype $STORETYPE \
  -keypass $SERVER_STORE_PWD \
  -keystore $SERVER_STORE \
  -storepass $SERVER_STORE_PWD

echo
echo "${H2}Creating server key and self-signed  certificate ...${RESET}"
keytool \
  -genkeypair \
  -alias $SERVER_ALIAS \
  -keyalg EC \
  -dname "CN=$SERVER_SELF_CN, OU=$ORGANIZATIONAL_UNIT, O=$ORGANIZATION, L=$CITY, ST=$STATE_OR_PROVINCE, C=$TWO_LETTER_COUNTRY_CODE" \
  -validity $VALIDITY \
  -storetype $STORETYPE \
  -keypass $SERVER_STORE_PWD \
  -keystore $SERVER_STORE \
  -storepass $SERVER_STORE_PWD
keytool \
  -exportcert \
  -alias $SERVER_ALIAS \
  -keystore $SERVER_STORE \
  -storepass $SERVER_STORE_PWD |
  keytool \
    -importcert \
    -alias $SERVER_SELF_ALIAS \
    -keystore $SERVER_STORE \
    -storepass $SERVER_STORE_PWD \
    -noprompt

echo
echo "${H2}Creating server certificate signed by root CA...${RESET}"
keytool \
  -certreq \
  -alias $SERVER_ALIAS \
  -dname "CN=$SERVER_CN, OU=$ORGANIZATIONAL_UNIT, O=$ORGANIZATION, L=$CITY, ST=$STATE_OR_PROVINCE, C=$TWO_LETTER_COUNTRY_CODE" \
  -keystore $SERVER_STORE \
  -storepass $SERVER_STORE_PWD |
  keytool \
    -gencert \
    -alias $ROOT_KEY_ALIAS \
    -keystore $SERVER_STORE \
    -storepass $SERVER_STORE_PWD \
    -storetype $STORETYPE \
    -validity $VALIDITY |
  keytool \
    -importcert \
    -alias $SERVER_ALIAS \
    -keystore $SERVER_STORE \
    -storepass $SERVER_STORE_PWD

echo
echo "${H2}Creating bootstrap key and self-signed  certificate ...${RESET}"
keytool \
  -genkeypair \
  -alias $BOOTSTRAP_ALIAS \
  -keyalg EC \
  -dname "CN=$BOOTSTRAP_SELF_CN, OU=$ORGANIZATIONAL_UNIT, O=$ORGANIZATION, L=$CITY, ST=$STATE_OR_PROVINCE, C=$TWO_LETTER_COUNTRY_CODE" \
  -validity $VALIDITY \
  -storetype $STORETYPE \
  -keypass $SERVER_STORE_PWD \
  -keystore $SERVER_STORE \
  -storepass $SERVER_STORE_PWD
keytool \
  -exportcert \
  -alias $BOOTSTRAP_ALIAS \
  -keystore $SERVER_STORE \
  -storepass $SERVER_STORE_PWD |
  keytool \
    -importcert \
    -alias $BOOTSTRAP_SELF_ALIAS \
    -keystore $SERVER_STORE \
    -storepass $SERVER_STORE_PWD \
    -noprompt

echo
echo "${H2}Creating bootstrap certificate signed by root CA...${RESET}"
keytool \
  -certreq \
  -alias $BOOTSTRAP_ALIAS \
  -dname "CN=$BOOTSTRAP_CN, OU=$ORGANIZATIONAL_UNIT, O=$ORGANIZATION, L=$CITY, ST=$STATE_OR_PROVINCE, C=$TWO_LETTER_COUNTRY_CODE" \
  -keystore $SERVER_STORE \
  -storepass $SERVER_STORE_PWD |
  keytool \
    -gencert \
    -alias $ROOT_KEY_ALIAS \
    -keystore $SERVER_STORE \
    -storepass $SERVER_STORE_PWD \
    -storetype $STORETYPE \
    -validity $VALIDITY |
  keytool \
    -importcert \
    -alias $BOOTSTRAP_ALIAS \
    -keystore $SERVER_STORE \
    -storepass $SERVER_STORE_PWD

if [ "$client_start" -lt "$client_finish" ]; then
  echo
  echo "${H2}Import root certificate just to be able to import  need by root CA with expected CN to $CLIENT_STORE${RESET}"
  keytool \
    -exportcert \
    -alias $ROOT_KEY_ALIAS \
    -keystore $SERVER_STORE \
    -storepass $SERVER_STORE_PWD |
    keytool \
      -importcert \
      -alias $ROOT_KEY_ALIAS \
      -keystore $CLIENT_STORE \
      -storepass $CLIENT_STORE_PWD \
      -noprompt
fi

cert_end_point() {
  echo
  echo "${H1}Client Keystore : ${RESET}"
  echo "${H1}==================${RESET}"
  echo "${H2}Creating client key and self-signed certificate with expected CN CLIENT_ALIAS: $CLIENT_ALIAS${RESET}"
  keytool \
    -genkeypair \
    -alias $CLIENT_ALIAS \
    -keyalg EC \
    -dname "CN=$CLIENT_SELF_CN, OU=$ORGANIZATIONAL_UNIT, O=$ORGANIZATION, L=$CITY, ST=$STATE_OR_PROVINCE, C=$TWO_LETTER_COUNTRY_CODE" \
    -validity $VALIDITY \
    -storetype $STORETYPE \
    -keypass $CLIENT_STORE_PWD \
    -keystore $CLIENT_STORE \
    -storepass $CLIENT_STORE_PWD
  keytool \
    -exportcert \
    -alias $CLIENT_ALIAS \
    -keystore $CLIENT_STORE \
    -storepass $CLIENT_STORE_PWD |
    keytool \
      -importcert \
      -alias $CLIENT_SELF_ALIAS \
      -keystore $CLIENT_STORE \
      -storepass $CLIENT_STORE_PWD \
      -noprompt
#
#  echo
#  echo "${H2}Import root certificate just to be able to import  ned by root CA with expected CN...${RESET}"
#  keytool \
#    -exportcert \
#    -alias $ROOT_KEY_ALIAS \
#    -keystore $SERVER_STORE \
#    -storepass $SERVER_STORE_PWD |
#    keytool \
#      -importcert \
#      -alias $ROOT_KEY_ALIAS \
#      -keystore $CLIENT_STORE \
#      -storepass $CLIENT_STORE_PWD \
#      -noprompt
#

  echo
  echo "${H2}Creating client certificate signed by root CA with expected CN CLIENT_ALIAS: $CLIENT_ALIAS CLIENT_CN: $CLIENT_CN${RESET}"
  keytool \
    -certreq \
    -alias $CLIENT_ALIAS \
    -dname "CN=$CLIENT_CN, OU=$ORGANIZATIONAL_UNIT, O=$ORGANIZATION, L=$CITY, ST=$STATE_OR_PROVINCE, C=$TWO_LETTER_COUNTRY_CODE" \
    -keystore $CLIENT_STORE \
    -storepass $CLIENT_STORE_PWD |
    keytool \
      -gencert \
      -alias $ROOT_KEY_ALIAS \
      -keystore $SERVER_STORE \
      -storepass $SERVER_STORE_PWD \
      -storetype $STORETYPE \
      -validity $VALIDITY |
    keytool \
      -importcert \
      -alias $CLIENT_ALIAS \
      -keystore $CLIENT_STORE \
      -storepass $CLIENT_STORE_PWD \
      -noprompt
}

if [ "$client_start" -lt "$client_finish" ]; then
  echo "==Start Client=="
  while [ "$CLIENT_NUMBER" -lt "$client_finish" ]; do
    echo "number $CLIENT_NUMBER"
    echo "finish $client_finish"
    CLIENT_CN=$(end_point)
    CLIENT_ALIAS=$(client_alias_point)
    CLIENT_SELF_ALIAS=$(client_self_alias_point)
    echo "CLIENT_CN $CLIENT_CN"
    echo "CLIENT_ALIAS $CLIENT_ALIAS"
    echo "CLIENT_SELF_ALIAS $CLIENT_SELF_ALIAS"
    cert_end_point
    CLIENT_NUMBER=$(($CLIENT_NUMBER + 1))
    echo
  done
fi

echo
echo "${H0}!!! Warning ${H2}Migrate ${H1}${SERVER_STORE} ${H2}to ${H1}PKCS12 ${H2}which is an industry standard format..${RESET}"
keytool \
  -importkeystore \
  -srckeystore $SERVER_STORE \
  -destkeystore $SERVER_STORE \
  -deststoretype pkcs12 \
  -srcstorepass $SERVER_STORE_PWD

if [ "$client_start" -lt "$client_finish" ]; then
  echo
  echo "${H0}!!! Warning ${H2}Migrate ${H1}${CLIENT_STORE} ${H2}to ${H1}PKCS12 ${H2}which is an industry standard format..${RESET}"
  keytool \
    -importkeystore \
    -srckeystore $CLIENT_STORE \
    -destkeystore $CLIENT_STORE \
    -deststoretype pkcs12 \
    -srcstorepass $CLIENT_STORE_PWD

    echo "END_Client"
fi

echo "END_Bash..."
