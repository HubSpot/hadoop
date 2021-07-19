#!/bin/bash

CONTAINER_TEMP_OUTPUT_DIR=/temporary_artifacts
CONTAINER_RPMS_OUTPUT_DIR=/generated_rpms
CONTAINER="docker.hubteam.com/apache-hadoop-build-container/apache-hadoop-build-container:latest"

start_dockerd.sh

docker run \
    --network host \
    --rm \
    --mount type=bind,src="$WORKSPACE",dst="$WORKSPACE" \
    --mount type=bind,src="$RPMS_OUTPUT_DIR_CENTOS_6",dst="$CONTAINER_RPMS_OUTPUT_DIR" \
    --mount type=bind,src="$JAVA_HOME",dst="$JAVA_HOME,readonly" \
    --mount type=bind,src="$MAVEN_DIR",dst="$MAVEN_DIR,readonly" \
    --mount type=bind,src="/root/.m2",dst="/root/.m2" \
    --volume "$CONTAINER_TEMP_OUTPUT_DIR" \
    --workdir "$PWD" \
    --env TEMP_OUTPUT_DIR="$CONTAINER_TEMP_OUTPUT_DIR" \
    --env RPMS_OUTPUT_DIR="$CONTAINER_RPMS_OUTPUT_DIR" \
    --env PKG_RELEASE \
    --env MAVEN_DIR \
    --env HADOOP_VERSION \
    --env JAVA_HOME \
    "$CONTAINER" \
    "./build.sh"