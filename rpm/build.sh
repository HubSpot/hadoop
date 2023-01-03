#!/bin/bash
set -e
set -x

# the pom.xml has an invalid xml namespace, so just remove that so xmllint can parse it.
cat $WORKSPACE/pom.xml | sed '15 s/xmlns=".*"//g' > $TEMP_OUTPUT_DIR/pom.xml.tmp
HADOOP_VERSION=$(echo "cat /project/version/text()" | xmllint --nocdata --shell $TEMP_OUTPUT_DIR/pom.xml.tmp | sed '1d;$d')
export HADOOP_VERSION
rm $TEMP_OUTPUT_DIR/pom.xml.tmp
# sanity check that we've got some that looks right. it wouldn't be the end of the world if we got it wrong, but
# will help avoid confusion.
if [[ ! "$HADOOP_VERSION" =~ 3\.[0-9]+\.[0-9]+ ]]; then
    echo "Unexpected HADOOP_VERSION extracted from pom.xml. Got $HADOOP_VERSION but expected a string like '3.3.1', with three numbers separated by decimals, the first numbers being 3."
    exit 1
fi
echo "Building Hadoop version $HADOOP_VERSION"

# MAIN_BRANCH goes to MAIN_YUM_REPO, with release hs.buildNumber
# All others go to DEVELOP_YUM_REPO, with release hs~branch.buildNumber
MAIN_BRANCH="hubspot-3.3"
# We want our resulting version to follow this schema:
# master branch: {hadoop_version}-hs.{build_number}.el8
# other branches: {hadoop_version}-hs~{branch_name}.{build_number}.el8, where branch_name substitutes underscore for non-alpha-numeric characters
MAIN_YUM_REPO="8_hs-hadoop"
DEVELOP_YUM_REPO="8_hs-hadoop-develop"
release_prefix="hs"
if [ "$GIT_BRANCH" = "$MAIN_BRANCH" ]; then
    repo=$MAIN_YUM_REPO
else
    release_prefix="${release_prefix}~${GIT_BRANCH//[^[:alnum:]]/_}"
    repo=$DEVELOP_YUM_REPO
fi
release="${release_prefix}.${BUILD_NUMBER}"
export PKG_RELEASE=$release
export YUM_REPO_UPLOAD_OVERRIDE=$repo
echo "Will upload package with release $release to $repo"

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
