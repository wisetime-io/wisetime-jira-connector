#!/usr/bin/env bash
set -o errexit ; set -o errtrace ; set -o pipefail

if [[ -z "${AWS_ACCESS_KEY_ID}" ]] && [[ -z "${AWS_SECRET_KEY}" ]]
then
   echo "\AWS secrets are empty, skipping (pull request)"
else
   export GRADLE_USER_HOME=/gradle/
   export GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.internal.launcher.welcomeMessageEnabled=false -Daws.accessKeyId=${AWS_ACCESS_KEY_ID} -Daws.secretKey=${AWS_SECRET_KEY}"
   ./gradlew --info -PreqPublish=true publish -x check -x test
fi
