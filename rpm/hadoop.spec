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
#
# Hadoop RPM spec file
#

# FIXME: we need to disable a more strict checks on native files for now,
# since Hadoop build system makes it difficult to pass the kind of flags
# that would make newer RPM debuginfo generation scripts happy.
%undefine _missing_build_ids_terminate_build

%define major_version 3.3
%define etc_hadoop /etc/%{name}
%define etc_yarn /etc/yarn
%define config_hadoop %{etc_hadoop}/conf
%define config_yarn %{etc_yarn}/conf

%define usr_lib /usr/lib
%define lib_hadoop %{usr_lib}/%{name}
%define lib_hadoop_common %{usr_lib}/%{name}/share/hadoop/common
%define lib_hdfs %{lib_hadoop}/share/hadoop/hdfs
%define lib_yarn %{lib_hadoop}/share/hadoop/yarn
%define lib_mapreduce %{lib_hadoop}/share/hadoop/mapreduce

%define log_hadoop_dirname /var/log
%define log_hadoop %{log_hadoop_dirname}/%{name}
%define log_yarn %{log_hadoop_dirname}/%{name}-yarn
%define log_hdfs %{log_hadoop_dirname}/%{name}-hdfs
%define log_httpfs %{log_hadoop_dirname}/%{name}-httpfs
%define log_kms %{log_hadoop_dirname}/%{name}-kms
%define log_mapreduce %{log_hadoop_dirname}/%{name}-mapreduce

%define run_hadoop_dirname /var/run
%define run_hadoop %{run_hadoop_dirname}/hadoop
%define run_yarn %{run_hadoop_dirname}/%{name}-yarn
%define run_hdfs %{run_hadoop_dirname}/%{name}-hdfs
%define run_httpfs %{run_hadoop_dirname}/%{name}-httpfs
%define run_kms %{run_hadoop_dirname}/%{name}-kms
%define run_mapreduce %{run_hadoop_dirname}/%{name}-mapreduce

%define state_hadoop_dirname /var/lib
%define state_hadoop %{state_hadoop_dirname}/hadoop
%define state_yarn %{state_hadoop_dirname}/%{name}-yarn
%define state_hdfs %{state_hadoop_dirname}/%{name}-hdfs
%define state_mapreduce %{state_hadoop_dirname}/%{name}-mapreduce
%define state_httpfs %{state_hadoop_dirname}/%{name}-httpfs
%define state_kms %{state_hadoop_dirname}/%{name}-kms

%define man_hadoop %{_mandir}

%define httpfs_services httpfs
%define kms_services kms
%define mapreduce_services mapreduce-historyserver
%define hdfs_services hdfs-namenode hdfs-secondarynamenode hdfs-datanode hdfs-zkfc hdfs-journalnode hdfs-dfsrouter
%define yarn_services yarn-resourcemanager yarn-nodemanager yarn-proxyserver yarn-timelineserver yarn-router
%define hadoop_services %{hdfs_services} %{mapreduce_services} %{yarn_services} %{httpfs_services} %{kms_services}

%ifarch i386
%global hadoop_arch Linux-i386-32
%endif
%ifarch amd64 x86_64
%global hadoop_arch Linux-amd64-64
%endif


# FIXME: brp-repack-jars uses unzip to expand jar files
# Unfortunately aspectjtools-1.6.5.jar pulled by ivy contains some files and directories without any read permission
# and make whole process to fail.
# So for now brp-repack-jars is being deactivated until this is fixed.
# See BIGTOP-294
%define __os_install_post \
    %{_rpmconfigdir}/brp-compress ; \
    %{_rpmconfigdir}/brp-strip-static-archive %{__strip} ; \
    %{_rpmconfigdir}/brp-strip-comment-note %{__strip} %{__objdump} ; \
    /usr/lib/rpm/brp-python-bytecompile ; \
    %{nil}

%define netcat_package nc
%define alternatives_cmd alternatives
%global initd_dir %{_sysconfdir}/rc.d/init.d


# Even though we split the RPM into arch and noarch, it still will build and install
# the entirety of hadoop. Defining this tells RPM not to fail the build
# when it notices that we didn't package most of the installed files.
%define _unpackaged_files_terminate_build 0

# RPM searches perl files for dependancies and this breaks for non packaged perl lib
# like thrift so disable this
%define _use_internal_dependency_generator 0

# BIGTOP-3359
%define _build_id_links none

Name: hadoop
Version: %{hadoop_version}
Release: %{release}
Summary: Combines all hadoop libraries into a single package for ease of versioning.
License: ASL 2.0
URL: http://hadoop.apache.org/core/
Group: Development/Libraries
Source0: %{input_tar}
Source1: do-component-build
Source2: install_%{name}.sh
Source3: hadoop.default
Source5: httpfs.default
Source6: hadoop.1
Source7: hdfs.conf
Source8: yarn.conf
Source9: mapreduce.conf
Source10: init.d.tmpl
Source11: hadoop-hdfs-namenode.svc
Source12: hadoop-hdfs-datanode.svc
Source13: hadoop-hdfs-secondarynamenode.svc
Source14: hadoop-mapreduce-historyserver.svc
Source15: hadoop-yarn-resourcemanager.svc
Source16: hadoop-yarn-nodemanager.svc
Source17: hadoop-httpfs.svc
Source18: mapreduce.default
Source19: hdfs.default
Source20: yarn.default
Source21: hadoop-hdfs-zkfc.svc
Source22: hadoop-hdfs-journalnode.svc
Source23: yarn.1
Source24: hdfs.1
Source25: mapred.1
Source26: hadoop-yarn-timelineserver.svc
Source27: hadoop-kms.svc
Source28: kms.default

Buildroot: %{_tmppath}/%{name}-%{version}-%{release}-root-%(%{__id} -u -n)

Requires: coreutils, /usr/sbin/useradd, /usr/sbin/usermod, /sbin/chkconfig, /sbin/service
Requires: psmisc, %{netcat_package}
Requires: openssl-devel
Requires: coreutils, /lib/lsb/init-functions

# Sadly, Sun/Oracle JDK in RPM form doesn't provide libjvm.so, which means we have
# to set AutoReq to no in order to minimize confusion. Not ideal, but seems to work.
# I wish there was a way to disable just one auto dependency (libjvm.so)
AutoReq: no

# CentOS 5 does not have any dist macro
# So I will suppose anything that is not Mageia or a SUSE will be a RHEL/CentOS/Fedora
BuildRequires: pkgconfig, redhat-rpm-config, lzo-devel, openssl-devel

%if %{?el7}0
BuildRequires: cmake3
%else
BuildRequires: cmake
%endif

%description
Combines all hadoop libraries (common, hdfs, mapred, yarn) into a single library package. This
simplifies deployment via puppet because there are fewer versions to keep in sync, a thing puppet does
not do well when needing to downgrade.

See https://git.hubteam.com/HubSpot/HBasePlanning/issues/591 for more info on combining libraries.
See https://hadoop.apache.org/ for more info on hadoop.

%package hdfs-namenode
Summary: The Hadoop namenode manages the block locations of HDFS files
Group: System/Daemons
Requires: %{name} >= %{major_version}
Requires(pre): %{name} >= %{major_version}

%description hdfs-namenode
The Hadoop Distributed Filesystem (HDFS) requires one unique server, the
namenode, which manages the block locations of files on the filesystem.


%package hdfs-zkfc
Summary: Hadoop HDFS failover controller
Group: System/Daemons
Requires: %{name} >= %{major_version}
Requires(pre): %{name} >= %{major_version}

%description hdfs-zkfc
The Hadoop HDFS failover controller is a ZooKeeper client which also
monitors and manages the state of the NameNode. Each of the machines
which runs a NameNode also runs a ZKFC, and that ZKFC is responsible
for: Health monitoring, ZooKeeper session management, ZooKeeper-based
election.

%package hdfs-journalnode
Summary: Hadoop HDFS JournalNode
Group: System/Daemons
Requires: %{name} >= %{major_version}
Requires(pre): %{name} >= %{major_version}

%description hdfs-journalnode
The HDFS JournalNode is responsible for persisting NameNode edit logs.
In a typical deployment the JournalNode daemon runs on at least three
separate machines in the cluster.

%package hdfs-datanode
Summary: Hadoop Data Node
Group: System/Daemons
Requires: %{name} >= %{major_version}
Requires(pre): %{name} >= %{major_version}

%description hdfs-datanode
The Data Nodes in the Hadoop Cluster are responsible for serving up
blocks of data over the network to Hadoop Distributed Filesystem
(HDFS) clients.

%package hdfs-dfsrouter
Summary: HDFS Router Server
Group: System/Daemons
Requires: %{name} >= %{major_version}
Requires(pre): %{name} >= %{major_version}

%description hdfs-dfsrouter
HDFS Router Server which supports Router Based Federation.

%package httpfs
Summary: HTTPFS for Hadoop
Group: System/Daemons
Requires: %{name} >= %{major_version}
Requires(pre): %{name} >= %{major_version}

%description httpfs
The server providing HTTP REST API support for the complete FileSystem/FileContext
interface in HDFS.

%package kms
Summary: KMS for Hadoop
Group: System/Daemons
Requires: %{name} >= %{major_version}
Requires(pre): %{name} >= %{major_version}

%description kms
Cryptographic Key Management Server based on Hadoop KeyProvider API.

%package yarn-resourcemanager
Summary: YARN Resource Manager
Group: System/Daemons
Requires: %{name} >= %{major_version}
Requires(pre): %{name} >= %{major_version}

%description yarn-resourcemanager
The resource manager manages the global assignment of compute resources to applications

%package yarn-nodemanager
Summary: YARN Node Manager
Group: System/Daemons
Requires: %{name} >= %{major_version}
Requires(pre): %{name} >= %{major_version}

%description yarn-nodemanager
The NodeManager is the per-machine framework agent who is responsible for
containers, monitoring their resource usage (cpu, memory, disk, network) and
reporting the same to the ResourceManager/Scheduler.

%package yarn-proxyserver
Summary: YARN Web Proxy
Group: System/Daemons
Requires: %{name} >= %{major_version}
Requires(pre): %{name} >= %{major_version}

%description yarn-proxyserver
The web proxy server sits in front of the YARN application master web UI.

%package yarn-timelineserver
Summary: YARN Timeline Server
Group: System/Daemons
Requires: %{name} >= %{major_version}
Requires(pre): %{name} >= %{major_version}

%description yarn-timelineserver
Storage and retrieval of applications' current as well as historic information in a generic fashion is solved in YARN through the Timeline Server.

%package mapreduce-historyserver
Summary: MapReduce History Server
Group: System/Daemons
Requires: %{name} >= %{major_version}
Requires(pre): %{name} >= %{major_version}

%description mapreduce-historyserver
The History server keeps records of the different activities being performed on a Apache Hadoop cluster

%package yarn-router
Summary: YARN Router Server
Group: System/Daemons
Requires: %{name} >= %{major_version}
Requires(pre): %{name} >= %{major_version}

%description yarn-router
YARN Router Server which supports YARN Federation.

%prep
%setup -n %{name}-%{hadoop_version}-src

%build
env \
  HADOOP_VERSION=%{hadoop_version} \
  HADOOP_ARCH=%{hadoop_arch}
bash %{SOURCE1}

%clean
%__rm -rf $RPM_BUILD_ROOT

#########################
#### INSTALL SECTION ####
#########################
%install
%__rm -rf $RPM_BUILD_ROOT

%__install -d -m 0755 $RPM_BUILD_ROOT/%{lib_hadoop}

env HADOOP_VERSION=%{hadoop_version} bash %{SOURCE2} \
  --distro-dir=$RPM_SOURCE_DIR \
  --build-dir=$PWD/build \
  --system-bin-dir=$RPM_BUILD_ROOT%{_bindir} \
  --system-include-dir=$RPM_BUILD_ROOT%{_includedir} \
  --system-lib-dir=$RPM_BUILD_ROOT%{_libdir} \
  --system-libexec-dir=$RPM_BUILD_ROOT/%{lib_hadoop}/libexec \
  --hadoop-etc-dir=$RPM_BUILD_ROOT%{etc_hadoop} \
  --prefix=$RPM_BUILD_ROOT \
  --native-build-string=%{hadoop_arch} \
  --man-dir=$RPM_BUILD_ROOT%{man_hadoop} \

# Init.d scripts
%__install -d -m 0755 $RPM_BUILD_ROOT/%{initd_dir}/

# Install top level /etc/default files
%__install -d -m 0755 $RPM_BUILD_ROOT/etc/default
%__cp $RPM_SOURCE_DIR/hadoop.default $RPM_BUILD_ROOT/etc/default/hadoop

# Generate the init.d scripts
for service in %{hadoop_services}
do
       bash %{SOURCE10} $RPM_SOURCE_DIR/%{name}-${service}.svc rpm $RPM_BUILD_ROOT/%{initd_dir}/%{name}-${service}
       cp $RPM_SOURCE_DIR/${service/-*/}.default $RPM_BUILD_ROOT/etc/default/%{name}-${service}
       chmod 644 $RPM_BUILD_ROOT/etc/default/%{name}-${service}
done

# Install security limits
%__install -d -m 0755 $RPM_BUILD_ROOT/etc/security/limits.d
%__install -m 0644 %{SOURCE7} $RPM_BUILD_ROOT/etc/security/limits.d/hdfs.conf
%__install -m 0644 %{SOURCE8} $RPM_BUILD_ROOT/etc/security/limits.d/yarn.conf
%__install -m 0644 %{SOURCE9} $RPM_BUILD_ROOT/etc/security/limits.d/mapreduce.conf

# /var/lib/*/cache
%__install -d -m 1777 $RPM_BUILD_ROOT/%{state_yarn}/cache
%__install -d -m 1777 $RPM_BUILD_ROOT/%{state_hdfs}/cache
%__install -d -m 1777 $RPM_BUILD_ROOT/%{state_mapreduce}/cache
# /var/log/*
%__install -d -m 0755 $RPM_BUILD_ROOT/%{log_yarn}
%__install -d -m 0755 $RPM_BUILD_ROOT/%{log_hdfs}
%__install -d -m 0755 $RPM_BUILD_ROOT/%{log_mapreduce}
%__install -d -m 0755 $RPM_BUILD_ROOT/%{log_httpfs}
%__install -d -m 0755 $RPM_BUILD_ROOT/%{log_kms}
# /var/run/*
%__install -d -m 0755 $RPM_BUILD_ROOT/%{run_yarn}
%__install -d -m 0755 $RPM_BUILD_ROOT/%{run_hdfs}
%__install -d -m 0755 $RPM_BUILD_ROOT/%{run_mapreduce}
%__install -d -m 0755 $RPM_BUILD_ROOT/%{run_httpfs}
%__install -d -m 0755 $RPM_BUILD_ROOT/%{run_kms}

%pre
# hadoop
getent group hadoop >/dev/null || groupadd -r hadoop

#hdfs
getent group hdfs >/dev/null   || groupadd -r hdfs
getent passwd hdfs >/dev/null || /usr/sbin/useradd --comment "Hadoop HDFS" --shell /bin/bash -M -r -g hdfs -G hadoop --home %{state_hdfs} hdfs

# yarn
getent group yarn >/dev/null   || groupadd -r yarn
getent passwd yarn >/dev/null || /usr/sbin/useradd --comment "Hadoop Yarn" --shell /bin/bash -M -r -g yarn -G hadoop --home %{state_yarn} yarn

# mapreduce
getent group mapred >/dev/null   || groupadd -r mapred
getent passwd mapred >/dev/null || /usr/sbin/useradd --comment "Hadoop MapReduce" --shell /bin/bash -M -r -g mapred -G hadoop --home %{state_mapreduce} mapred

%pre httpfs
getent group httpfs >/dev/null   || groupadd -r httpfs
getent passwd httpfs >/dev/null || /usr/sbin/useradd --comment "Hadoop HTTPFS" --shell /bin/bash -M -r -g httpfs -G httpfs --home %{run_httpfs} httpfs

%pre kms
getent group kms >/dev/null   || groupadd -r kms
getent passwd kms >/dev/null || /usr/sbin/useradd --comment "Hadoop KMS" --shell /bin/bash -M -r -g kms -G kms --home %{state_kms} kms

%post
%{alternatives_cmd} --install %{config_hadoop} %{name}-conf %{etc_hadoop}/conf.dist 10

%post kms
chkconfig --add %{name}-kms

%post httpfs
chkconfig --add %{name}-httpfs

%files
%defattr(-,root,root)
%config(noreplace) %{etc_hadoop}/conf.dist/core-site.xml
%config(noreplace) %{etc_hadoop}/conf.dist/hadoop-metrics2.properties
%config(noreplace) %{etc_hadoop}/conf.dist/log4j.properties
%config(noreplace) %{etc_hadoop}/conf.dist/workers
%config(noreplace) %{etc_hadoop}/conf.dist/ssl-client.xml.example
%config(noreplace) %{etc_hadoop}/conf.dist/ssl-server.xml.example
%config(noreplace) %{etc_hadoop}/conf.dist/configuration.xsl
%config(noreplace) %{etc_hadoop}/conf.dist/hadoop-env.sh
%config(noreplace) %{etc_hadoop}/conf.dist/hadoop-policy.xml
%config(noreplace) /etc/default/hadoop
/etc/bash_completion.d/hadoop
%{lib_hadoop}
%{man_hadoop}/man1/hadoop.1.*
%{man_hadoop}/man1/yarn.1.*
%{man_hadoop}/man1/hdfs.1.*
%{man_hadoop}/man1/mapred.1.*
%{_bindir}/hadoop

# yarn
%config(noreplace) %{etc_hadoop}/conf.dist/yarn-env.sh
%config(noreplace) %{etc_hadoop}/conf.dist/yarn-site.xml
%config(noreplace) %{etc_hadoop}/conf.dist/capacity-scheduler.xml
%config(noreplace) %{etc_hadoop}/conf.dist/container-executor.cfg
%config(noreplace) /etc/security/limits.d/yarn.conf
%attr(0775,yarn,hadoop) %{run_yarn}
%attr(0775,yarn,hadoop) %{log_yarn}
%attr(0755,yarn,hadoop) %{state_yarn}
%attr(1777,yarn,hadoop) %{state_yarn}/cache
%{_bindir}/yarn
%{_bindir}/container-executor

# hdfs
%config(noreplace) %{etc_hadoop}/conf.dist/hdfs-site.xml
%config(noreplace) /etc/security/limits.d/hdfs.conf
%attr(0775,hdfs,hadoop) %{run_hdfs}
%attr(0775,hdfs,hadoop) %{log_hdfs}
%attr(0755,hdfs,hadoop) %{state_hdfs}
%attr(1777,hdfs,hadoop) %{state_hdfs}/cache
%{_bindir}/hdfs

# mapreduce
%config(noreplace) %{etc_hadoop}/conf.dist/mapred-site.xml
%config(noreplace) %{etc_hadoop}/conf.dist/mapred-env.sh
%config(noreplace) %{etc_hadoop}/conf.dist/mapred-queues.xml.template
%config(noreplace) /etc/security/limits.d/mapreduce.conf
%attr(0775,mapred,hadoop) %{run_mapreduce}
%attr(0775,mapred,hadoop) %{log_mapreduce}
%attr(0775,mapred,hadoop) %{state_mapreduce}
%attr(1777,mapred,hadoop) %{state_mapreduce}/cache
%{_bindir}/mapred

# libhdfs
%defattr(-,root,root)
%{_libdir}/libhdfs.*

# libhdfs-devel
%{_includedir}/hdfs.h

# libhdfspp
%defattr(-,root,root)
%{_libdir}/libhdfspp.*

# libhdfspp-devel
%{_includedir}/hdfspp

%files httpfs
%defattr(-,root,root)

%config(noreplace) /etc/default/%{name}-httpfs
%config(noreplace) %{etc_hadoop}/conf.dist/httpfs-env.sh
%config(noreplace) %{etc_hadoop}/conf.dist/httpfs-log4j.properties
%config(noreplace) %{etc_hadoop}/conf.dist/httpfs-site.xml
%{initd_dir}/%{name}-httpfs
%attr(0775,httpfs,httpfs) %{run_httpfs}
%attr(0775,httpfs,httpfs) %{log_httpfs}
%attr(0775,httpfs,httpfs) %{state_httpfs}

%files kms
%defattr(-,root,root)
%config(noreplace) %{etc_hadoop}/conf.dist/kms-acls.xml
%config(noreplace) %{etc_hadoop}/conf.dist/kms-env.sh
%config(noreplace) %{etc_hadoop}/conf.dist/kms-log4j.properties
%config(noreplace) %{etc_hadoop}/conf.dist/kms-site.xml
%config(noreplace) /etc/default/%{name}-kms
%{initd_dir}/%{name}-kms
%attr(0775,kms,kms) %{run_kms}
%attr(0775,kms,kms) %{log_kms}
%attr(0775,kms,kms) %{state_kms}

# Service file management RPMs
%define service_macro() \
%files %1 \
%defattr(-,root,root) \
%{initd_dir}/%{name}-%1 \
%config(noreplace) /etc/default/%{name}-%1 \
%post %1 \
chkconfig --add %{name}-%1

%service_macro hdfs-namenode
%service_macro hdfs-zkfc
%service_macro hdfs-journalnode
%service_macro hdfs-datanode
%service_macro hdfs-dfsrouter
%service_macro yarn-resourcemanager
%service_macro yarn-nodemanager
%service_macro yarn-proxyserver
%service_macro yarn-timelineserver
%service_macro yarn-router
%service_macro mapreduce-historyserver