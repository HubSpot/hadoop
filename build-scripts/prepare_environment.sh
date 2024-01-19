#
# Generates the appropriate environment vars so that we:
# - build against the right version of hadoop, and properly set up maven
# - generate the correct maven version based on the branches
# - upload RPMs with the correct release based on the branch, and to the right yum repo
#
# Since we need to distribute .blazar.yaml to all sub-modules of the project, we define our constants once
# in this script which can be re-used by every .blazar.yaml.
#
set -ex
printenv

# We base the expected main branch and resulting maven version for clients on the hadoop patch version
# The reason for this is hadoop re-branches for each patch release (3.3.1, 3.3.2, etc). At each re-branch
# the histories diverge. So we'll need to create our own fork of each new patch release branch.
# The convention is a fork named "hubspot-$patchVersion", and the maven coordinates "$patchVersion-hubspot-SNAPSHOT"
PATCH_VERSION="3.3.6"
MAIN_BRANCH="hubspot-${PATCH_VERSION}"

# If we bump our hadoop build version, we should bump this as well
# At some point it would be good to more closely link this to our hadoop build, but that can only happen
# once we update our apache-hadoop build to do a full maven. At which point we can probably change this to
# like 3.0-hubspot-SNAPSHOT and leave it at that.
MAVEN_ARGS="$MAVEN_AGS $VERSION_ARGS -Dgpg.skip=true -DskipTests=true -DskipTest -DskipITs  -Dmaven.install.skip=false -Dmaven.repo.local=$WORKSPACE/.m2"

MAVEN_ARGS="$MAVEN_ARGS -Phbase1"

#
# Validate inputs from blazar
#

if [ -z "$WORKSPACE" ]; then
    echo "Missing env var \$WORKSPACE"
    exit 1
fi
if [ -z "$GIT_BRANCH" ]; then
    echo "Missing env var \$GIT_BRANCH"
    exit 1
fi
if [ -z "$BUILD_COMMAND_RC_FILE" ]; then
    echo "Missing env var \$BUILD_COMMAND_RC_FILE"
    exit 1
fi

#
# Extract current hadoop version from root pom.xml
#

HADOOP_VERSION=$(echo "cat /project/version/text()" | xmllint --nocdata --shell $WORKSPACE/pom.xml | sed '1d;$d')

# Generate branch-specific env vars
# We are going to generate the maven version and the RPM release here:
# - For the maven version, we need to special case our main branch
# - For RPM, we want our final version to be:
#   main branch: {hadoop_version}-hs.{build_number}.el8
#   other branches: {hadoop_version}-hs~{branch_name}.{build_number}.el8, where branch_name substitutes underscore for non-alpha-numeric characters
#

echo "Git branch $GIT_BRANCH. Detecting appropriate version override and RPM release."

RELEASE="hs"

if [[ "$GIT_BRANCH" = "$MAIN_BRANCH" ]]; then
    MAVEN_VERSION="${PATCH_VERSION}-hubspot-SNAPSHOT"
elif [[ "$GIT_BRANCH" != "hubspot" ]]; then
    MAVEN_VERSION="${PATCH_VERSION}-${GIT_BRANCH}-SNAPSHOT"
    RELEASE="${RELEASE}~${GIT_BRANCH//[^[:alnum:]]/_}"
else
    echo "Invalid git branch $GIT_BRANCH"
    exit 1
fi

RELEASE="${RELEASE}.${BUILD_NUMBER}"
FULL_BUILD_VERSION="${HADOOP_VERSION}-${RELEASE}"

MAVEN_ARGS="$MAVEN_ARGS -Dhadoop.version=$MAVEN_VERSION"

write-build-env-var MAVEN_ARGS "$MAVEN_ARGS"
write-build-env-var SET_VERSION "$MAVEN_VERSION"
write-build-env-var PKG_RELEASE "$RELEASE"
write-build-env-var FULL_BUILD_VERSION "$FULL_BUILD_VERSION"

echo "Will use maven version $MAVEN_VERSION"
echo "Will run maven with extra args $MAVEN_ARGS"
