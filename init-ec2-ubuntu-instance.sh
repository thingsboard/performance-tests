#!/bin/sh
#
# Copyright © 2016-2022 The Thingsboard Authors
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

echo Init Ubuntu instance...

#extend the local port range up to 64500
cat /proc/sys/net/ipv4/ip_local_port_range
#32768  60999
echo "net.ipv4.ip_local_port_range = 1024 65535" | sudo tee -a /etc/sysctl.conf
echo "net.netfilter.nf_conntrack_max = 1048576" | sudo tee -a /etc/sysctl.conf
echo 'fs.file-max = 1048576' | sudo tee -a /etc/sysctl.conf
echo '*                soft    nofile          1048576' | sudo tee -a /etc/security/limits.conf
echo '*                hard    nofile          1048576' | sudo tee -a /etc/security/limits.conf
echo 'root             soft    nofile          1048576' | sudo tee -a /etc/security/limits.conf
echo 'root             hard    nofile          1048576' | sudo tee -a /etc/security/limits.conf
sudo -s sysctl -p
cat /proc/sys/net/ipv4/ip_local_port_range
#1024   65535
ulimit -n 1048576
#install software
sudo sysctl -w net.netfilter.nf_conntrack_max=1048576
sudo apt update
sudo apt install -y --no-install-recommends git maven docker docker-compose htop iotop mc screen
# manage Docker as a non-root user
sudo groupadd docker
sudo usermod -aG docker $USER
newgrp docker
# test non-root docker run
docker run hello-world
echo done
