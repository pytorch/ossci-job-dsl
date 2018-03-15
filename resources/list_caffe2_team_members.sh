#!/bin/bash

set -e

if [ "$#" -ne 1 ]; then
  echo "Usage: resources/list_caffe2_team_members.sh USERNAME"
  echo "  (password read off stdin)"
  exit 1
fi

user=$1
# This token must b a "Personal Access Token" with the "read:org" scope
read -s -p "GitHub password/token: " token

curl="curl -s -u ${user}:${token}"

function team_members_url() {
  ${curl} https://api.github.com/orgs/caffe2/teams | \
    jq -r .[0].members_url | \
    sed -e 's/{.*}//'
}


echo "// GitHub users that can authorize builds."
echo "// Dynamically generated from ./resources/$(basename $0)."
echo "static final List<String> githubAdmins = ["
for login in $(${curl} $(team_members_url) | jq -r .[].login | sort); do
  name=$(${curl} "https://api.github.com/users/${login}" | jq -r .name)
  echo "  '${login}', // ${name}"
done
echo "]"
