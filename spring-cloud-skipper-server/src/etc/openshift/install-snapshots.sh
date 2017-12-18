#!/usr/bin/env bash

base_dir=/tmp/skipper-osb-demo

# Create a base directory
mkdir ${base_dir} || rm -rf ${base_dir} && mkdir ${base_dir}
cd ${base_dir}

# Clone and build spring-cloud-deployer-openshift
git clone https://github.com/donovanmuller/spring-cloud-deployer-openshift.git && echo $(basename $_ .git) && cd $_
./mvnw install -DskipTests

# Clone and build spring-cloud-skipper-deployer-openshift
git clone https://github.com/donovanmuller/spring-cloud-skipper-platform-openshift.git && echo $(basename $_ .git) && cd $_
./mvnw install

# Clone and build spring-cloud-skipper
git clone https://github.com/donovanmuller/spring-cloud-skipper.git && echo $(basename $_ .git) && cd $_
git checkout service-broker
./mvnw install -DskipTests

# Build and push the Spring Cloud Skipper Docker image in the context of the minishift OpenShift instance
eval $(minishift --profile skipper docker-env)
./mvnw package dockerfile:build -pl :spring-cloud-skipper-server -DskipTests

# Clone and build spring-cloud-skipper-maven-plugin
git clone https://github.com/donovanmuller/spring-cloud-skipper-maven-plugin.git && echo $(basename $_ .git) && cd $_
./mvnw install


