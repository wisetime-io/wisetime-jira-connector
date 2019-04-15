#!/bin/sh

if [[ -z "${GITHUB_SSH_KEY_B64}" ]]
then
    # Will also be absent if we're not on master branch
    echo "\$GITHUB_SSH_KEY is empty, skipping (mirror)"
else
    # Ensure we can talk to GitHub
    mkdir -p ~/.ssh && \
    ssh-keyscan -t rsa github.com >> ~/.ssh/known_hosts && \
    # Prepare our GitHub private key
    mkdir -p /tmp/.ssh && \
    echo "${GITHUB_SSH_KEY_B64}" | base64 -d > /tmp/.ssh/github.key
    chmod 600 /tmp/.ssh/github.key && \
    # Push to GitHub mirror
    git remote set-url origin git@github.com:wisetime-io/wisetime-jira-connector.git && \
    GIT_SSH_COMMAND='ssh -i /tmp/.ssh/github.key' git push -u origin master && \
    # Cleanup in case we're running this locally on a dev machine
    git remote set-url origin ssh://git@stash.practiceinsight.io:7999/connect/wisetime-jira-connector.git && \
    rm -rf /tmp/.ssh && \
    echo "Push to GitHub mirror complete"

    if [ "$?" -ne "0" ]; then
        exit 1
    fi
fi
