#!/bin/bash -x
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

set -ex

usage() {
  echo "
usage: $0 <options>
  Required not-so-options:
     --distro-dir=DIR            path to distro specific files (debian/RPM)
     --build-dir=DIR             path to hive/build/dist
     --prefix=PREFIX             path to install into

  Optional options:
     --native-build-string       eg Linux-amd-64 (optional - no native installed if not set)
     ... [ see source for more similar options ]
  "
  exit 1
}

OPTS=$(getopt \
  -n $0 \
  -o '' \
  -l 'prefix:' \
  -l 'distro-dir:' \
  -l 'build-dir:' \
  -l 'native-build-string:' \
  -l 'hadoop-dir:' \
  -l 'client-dir:' \
  -l 'system-bin-dir:' \
  -l 'system-include-dir:' \
  -l 'system-lib-dir:' \
  -l 'system-libexec-dir:' \
  -l 'hadoop-etc-dir:' \
  -l 'man-dir:' \
  -l 'apache-branch:' \
  -- "$@")

if [ $? != 0 ] ; then
    usage
fi

eval set -- "$OPTS"
while true ; do
    case "$1" in
        --prefix)
        PREFIX=$2 ; shift 2
        ;;
        --distro-dir)
        DISTRO_DIR=$2 ; shift 2
        ;;
        --hadoop-dir)
        HADOOP_DIR=$2 ; shift 2
        ;;
        --client-dir)
        CLIENT_DIR=$2 ; shift 2
        ;;
        --system-bin-dir)
        SYSTEM_BIN_DIR=$2 ; shift 2
        ;;
        --system-include-dir)
        SYSTEM_INCLUDE_DIR=$2 ; shift 2
        ;;
        --system-lib-dir)
        SYSTEM_LIB_DIR=$2 ; shift 2
        ;;
        --system-libexec-dir)
        SYSTEM_LIBEXEC_DIR=$2 ; shift 2
        ;;
        --build-dir)
        BUILD_DIR=$2 ; shift 2
        ;;
        --native-build-string)
        NATIVE_BUILD_STRING=$2 ; shift 2
        ;;
        --hadoop-etc-dir)
        HADOOP_ETC_DIR=$2 ; shift 2
        ;;
        --man-dir)
        MAN_DIR=$2 ; shift 2
        ;;
        --)
        shift ; break
        ;;
        *)
        echo "Unknown option: $1"
        usage
        exit 1
        ;;
    esac
done

for var in PREFIX BUILD_DIR; do
  if [ -z "$(eval "echo \$$var")" ]; then
    echo Missing param: $var
    usage
  fi
done

HADOOP_DIR=${HADOOP_DIR:-$PREFIX/usr/lib/hadoop}
CLIENT_DIR=${CLIENT_DIR:-$PREFIX/usr/lib/hadoop/client}
SYSTEM_LIB_DIR=${SYSTEM_LIB_DIR:-$PREFIX/usr/lib}
SYSTEM_BIN_DIR=${SYSTEM_BIN_DIR:-$PREFIX/usr/bin}
MAN_DIR=${MAN_DIR:-$PREFIX/usr/man}
SYSTEM_INCLUDE_DIR=${SYSTEM_INCLUDE_DIR:-$PREFIX/usr/include}
SYSTEM_LIBEXEC_DIR=${SYSTEM_LIBEXEC_DIR:-$PREFIX/usr/libexec}
HADOOP_ETC_DIR=${HADOOP_ETC_DIR:-$PREFIX/etc/hadoop}
BASH_COMPLETION_DIR=${BASH_COMPLETION_DIR:-$PREFIX/etc/bash_completion.d}
HADOOP_NATIVE_LIB_DIR=${HADOOP_DIR}/lib/native

##Needed for some distros to find ldconfig
export PATH="/sbin/:$PATH"

#libexec
install -d -m 0755 ${SYSTEM_LIBEXEC_DIR}
cp -r ${BUILD_DIR}/libexec/* ${SYSTEM_LIBEXEC_DIR}/
rm -rf ${SYSTEM_LIBEXEC_DIR}/*.cmd

# hadoop jars
install -d -m 0755 ${HADOOP_DIR}/share
cp -a ${BUILD_DIR}/share/hadoop ${HADOOP_DIR}/share

# Copy bin files and make bin wrappers
# The wrapper is necessary to ensure HADOOP_LIBEXEC_DIR is properly 
# set before executing the actual underlying bin
install -d -m 0755 ${HADOOP_DIR}/bin
install -d -m 0755 $SYSTEM_BIN_DIR

function install_bin() {
  cp -a ${BUILD_DIR}/bin/$1 ${HADOOP_DIR}/bin
  wrapper=$SYSTEM_BIN_DIR/$1	
  cat > $wrapper <<EOF	
#!/bin/bash		
DEFAULTS_DIR=\${DEFAULTS_DIR-/etc/default}
[ -n "\${DEFAULTS_DIR}" -a -r \${DEFAULTS_DIR}/hadoop ] && . \${DEFAULTS_DIR}/hadoop

exec ${HADOOP_DIR#$PREFIX}/bin/$1 "\$@"	
EOF
  chmod 755 $wrapper
}

install_bin hadoop
install_bin hdfs
install_bin yarn
install_bin container-executor
install_bin mapred

# sbin
install -d -m 0755 ${HADOOP_DIR}/sbin
cp -a ${BUILD_DIR}/sbin/{hadoop-daemon,hadoop-daemons,workers,httpfs,kms}.sh ${HADOOP_DIR}/sbin
cp -a ${BUILD_DIR}/sbin/{distribute-exclude,refresh-namenodes}.sh ${HADOOP_DIR}/sbin
cp -a ${BUILD_DIR}/sbin/{yarn-daemon,yarn-daemons}.sh ${HADOOP_DIR}/sbin
cp -a ${BUILD_DIR}/sbin/mr-jobhistory-daemon.sh ${HADOOP_DIR}/sbin

# native libs
install -d -m 0755 ${SYSTEM_LIB_DIR}
install -d -m 0755 ${HADOOP_NATIVE_LIB_DIR}

for library in libhdfs.so.0.0.0 libhdfspp.so.0.1.0 ; do
  cp ${BUILD_DIR}/lib/native/${library} ${SYSTEM_LIB_DIR}/
  ldconfig -vlN ${SYSTEM_LIB_DIR}/${library}
  ln -s ${library} ${SYSTEM_LIB_DIR}/${library/.so.*/}.so
done

install -d -m 0755 ${SYSTEM_INCLUDE_DIR}
cp ${BUILD_DIR}/include/hdfs.h ${SYSTEM_INCLUDE_DIR}/
cp -r ${BUILD_DIR}/include/hdfspp ${SYSTEM_INCLUDE_DIR}/

cp ${BUILD_DIR}/lib/native/*.a ${HADOOP_NATIVE_LIB_DIR}/
for library in `cd ${BUILD_DIR}/lib/native ; ls libsnappy.so.1.* 2>/dev/null` libhadoop.so.1.0.0 libnativetask.so.1.0.0; do
  cp ${BUILD_DIR}/lib/native/${library} ${HADOOP_NATIVE_LIB_DIR}/
  ldconfig -vlN ${HADOOP_NATIVE_LIB_DIR}/${library}
  ln -s ${library} ${HADOOP_NATIVE_LIB_DIR}/${library/.so.*/}.so
done

# Bash tab completion
install -d -m 0755 $BASH_COMPLETION_DIR
install -m 0644 \
  hadoop-common-project/hadoop-common/src/contrib/bash-tab-completion/hadoop.sh \
  $BASH_COMPLETION_DIR/hadoop

# conf
install -d -m 0755 $HADOOP_ETC_DIR/conf.dist

# disable everything that's definied in hadoop-env.sh
# so that it can still be used as example, but doesn't affect anything
# by default
sed -i -e '/^[^#]/s,^,#,' ${BUILD_DIR}/etc/hadoop/hadoop-env.sh
cp -r ${BUILD_DIR}/etc/hadoop/* $HADOOP_ETC_DIR/conf.dist
rm -rf $HADOOP_ETC_DIR/conf.dist/*.cmd

# man pages
mkdir -p $MAN_DIR/man1
for manpage in hadoop hdfs yarn mapred; do
	gzip -c < $DISTRO_DIR/$manpage.1 > $MAN_DIR/man1/$manpage.1.gz
	chmod 644 $MAN_DIR/man1/$manpage.1.gz
done

# HTTPFS
install -d -m 0755 ${PREFIX}/var/lib/hadoop-httpfs

# KMS
install -d -m 0755 ${PREFIX}/var/lib/hadoop-kms

# Create log, var and lib
install -d -m 0755 $PREFIX/var/{log,run,lib}/hadoop-hdfs
install -d -m 0755 $PREFIX/var/{log,run,lib}/hadoop-yarn
install -d -m 0755 $PREFIX/var/{log,run,lib}/hadoop-mapreduce

# Remove all source and create version-less symlinks to offer integration point with other projects
find $HADOOP_DIR -name "*-sources.jar" -print -delete
find $HADOOP_DIR -name sources -type d -print -delete
for j in $(find $HADOOP_DIR/share/hadoop -maxdepth 2 -name "hadoop-*-${HADOOP_VERSION}.jar"); do
  ln -sf $j ${j/-[[:digit:]]*/.jar}
done

# Now create a client installation area full of symlinks
install -d -m 0755 ${CLIENT_DIR}
for file in `cat ${BUILD_DIR}/hadoop-client.list` ; do
  path=$(find ${HADOOP_DIR} -name $file -print -quit)
  [ -e $path ] &&
  ln -fs ${path#$PREFIX} ${CLIENT_DIR}/${file} && \
  ln -fs ${path#$PREFIX} ${CLIENT_DIR}/${file/-[[:digit:]]*/.jar} && \
  continue

  echo "Could not find or link $file in $HADOOP_DIR: $path"
  exit 1
done
