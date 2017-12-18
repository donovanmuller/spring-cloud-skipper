#!/usr/bin/env bash

eval $(minishift --profile skipper docker-env)
docker images | grep springcloud
if [ $? != 0 ]; then
    RED='\033[0;31m'
    ORANGE='\033[0;33m'
    NC='\033[0m'
    printf "${RED}The retrofitted Spring Cloud Skipper server Docker image has not been pushed. Please run the ${NC}${ORANGE}'install-snapshots.sh' ${NC}${RED}script${NC}\n"
    exit 1
fi

# Create a new 'skipper' project
oc new-project skipper \
    --description="Skipper demonstration project" \
    --display-name="Skipper" \
    || true # skip if already created

# Create the Route before the rest of the Skipper resources because we need the Route host that is generated.
# This Route host value is used as the Template parameter ('NEXUS_ROUTE_HOST') for the Skipper configuration
# to which remote Maven repository it should resolve artifacts from.
oc create -f spring-cloud-skipper-server/src/etc/openshift/nexus-route.yml -n skipper

# Process and create the Template that spins up Nexus and Skipper
oc process -f spring-cloud-skipper-server/src/etc/openshift/skipper-osb-nexus-template.yml \
    -p NEXUS_ROUTE_HOST=`oc get route nexus --template={{.spec.host}} -n skipper` | \
    oc create -f - -n skipper

# Add the 'edit' security role to the 'skipper' Service Account so it can access the OpenShift API's to deploy etc.
oc adm policy add-role-to-user edit system:serviceaccount:skipper:skipper -n skipper
