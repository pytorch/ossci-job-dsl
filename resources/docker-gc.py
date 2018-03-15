import json
import datetime
import pytz
import argparse
import sys
import re

import requests
from requests.auth import HTTPBasicAuth
import dateutil.parser

parser = argparse.ArgumentParser(description="Delete old Docker tags from registry")
parser.add_argument('base_url')
parser.add_argument('--dry-run',
                    action='store_true',
                    help="Dry run; print tags that would be deleted")
parser.add_argument('--keep-stable-days',
                    type=int,
                    default=14,
                    help="Days of stable Docker tags to keep (non per-build images)")
parser.add_argument('--keep-unstable-days',
                    type=int,
                    default=1,
                    help="Days of unstable Docker tags to keep (per-build images)")
parser.add_argument('--filter-prefix',
                    type=str,
                    help="Only run cleanup for repositories with this prefix")
parser.add_argument('--ignore-tags',
                    type=str,
                    help="Never cleanup these tags (comma separated)")
parser.add_argument('--verbose',
                    action='store_true',
                    help="Verbose mode; print every HTTP request")
parser.add_argument('--username',
                    type=str,
                    help="Username to auth with registry")
parser.add_argument('--password',
                    type=str,
                    help="Password to auth with registry")
parser.add_argument('--password-stdin',
                    action='store_true',
                    help="Read password from stdin")
args = parser.parse_args()


class AuthWrapper:
    def __init__(self, auth):
        self.auth = auth
        self.auth_header = None
        self.auth_basic = None

    def process_basic_auth(self, response):
        match = re.match('^BASIC (.*)', response.headers['Www-Authenticate'])
        if match is None:
            return False

        self.auth_basic = self.auth
        return True

    def process_bearer(self, response):
        match = re.match('^Bearer (.*)', response.headers['Www-Authenticate'])
        if match is None:
            return False

        params = {}
        for pair in match.group(1).split(','):
            match = re.match('^(.*)="(.*)"$', pair)
            params[match.group(1)] = match.group(2)
        # Talk to auth server to get a token
        realm = params['realm']
        del params['realm']
        response = requests.get(realm, params=params, auth=self.auth)
        if response.status_code != 200:
            print('{}: status {} ({})'.format(
                realm,
                response.status_code,
                response.text.strip()))
            sys.exit(1)
        self.auth_header = {
            'Authorization': 'Bearer {}'.format(response.json()['token']),
        }
        return True

    def do_auth(self, response):
        if self.process_basic_auth(response):
            return
        if self.process_bearer(response):
            return
        return

    def get_kwargs(self, headers):
        headers = headers.copy()
        if self.auth_header is not None:
            headers.update(self.auth_header)
        kwargs = {'headers': headers}
        if self.auth_basic is not None:
            kwargs['auth'] = self.auth_basic
        return kwargs

    def get(self, url, params={}, headers={}):
        response = None
        for _ in range(2):
            if response is not None and response.status_code == 401:
                self.do_auth(response)
            response = requests.get(url, params=params, **self.get_kwargs(headers))
            if response.status_code != 401:
                return response
        print("{}: unable to complete without 401...")
        sys.exit(1)

    def delete(self, url, params={}, headers={}):
        response = None
        for _ in range(2):
            if response is not None and response.status_code == 401:
                self.do_auth(response)
            response = requests.delete(url, params=params, **self.get_kwargs(headers))
            if response.status_code != 401:
                return response
        print("{}: unable to complete without 401...")
        sys.exit(1)

if args.dry_run:
    print("Dry run, not deleting any tags")

stable_window = datetime.timedelta(days=args.keep_stable_days)
unstable_window = datetime.timedelta(days=args.keep_unstable_days)
now = datetime.datetime.now(pytz.UTC)
ignore_tags = args.ignore_tags.split(',')

password = args.password
if args.password_stdin:
    # If your password has trailing newlines, you gonna have a bad time
    password = sys.stdin.read().rstrip('\n')

# Remove trailing / from base URL
args.base_url = re.sub('[/]+$', '', args.base_url)

# AuthWrapper takes care of getting a token from the auth server
# whenever the registry returns 401 unauthorized.
aw = AuthWrapper(HTTPBasicAuth(args.username, password))

# List repositories and filter by prefix
response = aw.get("{}/v2/_catalog".format(args.base_url)).json()
repos = []
for repo in response['repositories']:
    if repo.startswith(args.filter_prefix):
        repos.append(repo)

# Run cleanup for discovered repositories
for repo in repos:
    print(repo)

    v2_headers = {'Accept': 'application/vnd.docker.distribution.manifest.v2+json'}
    repo_url = "{}/v2/{}".format(args.base_url, repo)
    tags = aw.get("{}/tags/list".format(repo_url)).json()['tags']
    if tags is None:
        continue

    for tag in tags:
        if tag.isdigit():
            window = stable_window
        else:
            window = unstable_window

        url = "{}/manifests/{}".format(repo_url, tag)
        if args.verbose:
            print("{}: GET {}".format(tag, url))
        response = aw.get(url, headers=v2_headers)
        if response.status_code != 200:
            print("{}: response status {}".format(tag, response.status_code))
            continue

        config_digest = response.json()['config']['digest']
        manifest_digest = response.headers['Docker-Content-Digest']

        # Get configuration to figure out when this was created
        url = "{}/blobs/{}".format(repo_url, config_digest)
        if args.verbose:
            print("{}: GET {}".format(tag, url))
        response = aw.get(url)
        if response.status_code != 200:
            print("{}: response status {}".format(tag, response.status_code))
            continue

        created = dateutil.parser.parse(response.json()['created'])
        age = (now - created)
        if tag in ignore_tags:
            print("Ignoring tag {} (age: {})".format(tag, age))
            continue
        if age < window:
            print("Not deleting manifest for tag {} (age: {})".format(tag, age))
            continue

        if args.dry_run:
            print("(dry run) Deleting manifest for tag {} (age: {})".format(tag, age))
        else:
            print("Deleting manifest for tag {} (age: {})".format(tag, age))
            url = "{}/manifests/{}".format(repo_url, manifest_digest)
            if args.verbose:
                print("{}: DELETE {}".format(tag, url))
            response = aw.delete(url)
            if response.status_code / 100 != 2:
                print("{}: response status {}".format(tag, response.status_code))
