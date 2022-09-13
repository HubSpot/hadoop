#!/bin/bash
set -e
set -x

if [[ "X$HADOOP_VERSION" = "X" ]]; then
    echo "HADOOP_VERSION not set"
    exit 1
fi

if [[ "X$MAVEN_DIR" = "X" ]]; then
    MAVEN_DIR="/opt/build-deps/maven3"
fi

export PATH="$PATH:$MAVEN_DIR/bin"

RPM_DIR="$TEMP_OUTPUT_DIR"

# Setup scratch dir
SCRATCH_DIR="${RPM_DIR}/scratch"

rm -rf $SCRATCH_DIR
mkdir -p ${SCRATCH_DIR}/{SOURCES,SPECS,RPMS,SRPMS}
cp -r sources/* ${SCRATCH_DIR}/SOURCES/
cp hadoop.spec ${SCRATCH_DIR}/SPECS/

# Set up src dir
export SRC_DIR="${RPM_DIR}/hadoop-$HADOOP_VERSION-src"
TAR_NAME=hadoop-$HADOOP_VERSION-src.tar.gz

rm -rf $SRC_DIR
rsync -a ../ $SRC_DIR --exclude rpm --exclude .git --exclude .docker-data

cd $RPM_DIR

tar -czf ${SCRATCH_DIR}/SOURCES/${TAR_NAME} $(basename $SRC_DIR)

# Build srpm

rpmbuild \
    --define "_topdir $SCRATCH_DIR" \
    --define "input_tar $TAR_NAME" \
    --define "hadoop_version ${HADOOP_VERSION}" \
    --define "release ${PKG_RELEASE}%{?dist}" \
    -bs --nodeps --buildroot="${SCRATCH_DIR}/INSTALL" \
    ${SCRATCH_DIR}/SPECS/hadoop.spec

src_rpm=$(ls -1 ${SCRATCH_DIR}/SRPMS/hadoop-*)

# build rpm

rpmbuild \
    --define "_topdir $SCRATCH_DIR" \
    --define "input_tar $TAR_NAME" \
    --define "hadoop_version ${HADOOP_VERSION}" \
    --define "release ${PKG_RELEASE}%{?dist}" \
    --rebuild $src_rpm

if [[ -d $RPMS_OUTPUT_DIR ]]; then
    # Move rpms to output dir for upload

    find ${SCRATCH_DIR}/{SRPMS,RPMS} -name "*.rpm" -exec mv {} $RPMS_OUTPUT_DIR/ \;
fi