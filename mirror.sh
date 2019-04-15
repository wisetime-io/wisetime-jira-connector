#!/bin/sh

if [[ -z "${GITHUB_SSH_KEY_B64}" ]]
then
    # Will also be absent if we're not on master branch
    echo "\$GITHUB_SSH_KEY is empty, skipping (mirror)"
else
    git fetch --tags
    # Ensure we can talk to GitHub
    mkdir -p ~/.ssh && \
    ssh-keyscan -t rsa github.com >> ~/.ssh/known_hosts && \
    # Prepare our GitHub private key
    mkdir -p /tmp/.ssh && \
    echo "${GITHUB_SSH_KEY_B64}" | base64 -d > /tmp/.ssh/github.key
    chmod 600 /tmp/.ssh/github.key && \
    # Push to GitHub mirror
    git remote add github git@github.com:wisetime-io/wisetime-jira-connector.git && \
    GIT_SSH_COMMAND='ssh -i /tmp/.ssh/github.key' git push github master && \
    GIT_SSH_COMMAND='ssh -i /tmp/.ssh/github.key' git push github --tags && \
    rm -rf /tmp/.ssh && \
    echo "Push to GitHub mirror complete"

    if [ "$?" -ne "0" ]; then
        exit 1
    fi
fi
