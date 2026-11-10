#!/usr/bin/env python3
"""Local Keycloak (OIDC) for Text-to-SQL: realm, 3 sample accounts and Compose control.

Usage (from the repository root):
    python infra/local/oidc.py init     # generate realm + private passwords under .local/oidc/
    python infra/local/oidc.py up       # start Keycloak (http://127.0.0.1:8180)
    python infra/local/oidc.py check    # verify the realm issuer is reachable
    python infra/local/oidc.py token viewer   # print an access token for local API testing
    python infra/local/oidc.py stop | status

Passwords, user IDs and the realm file stay in the ignored .local/ folder; nothing secret is
printed except the explicitly requested local access token.
"""

import argparse
import json
import os
import secrets
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
CONFIG = ROOT / ".local" / "oidc"
ACCOUNTS = CONFIG / "accounts.json"
ISSUER = "http://127.0.0.1:8180/realms/nla"
WEB_CLIENT = "nla-web"
CLI_CLIENT = "nla-local-cli"
AUDIENCE = "nla-api"
# Backlog US-1.2: exactly three roles and three sample accounts.
ACCOUNT_ROLES = {"admin": "ADMIN", "analyst": "ANALYST", "viewer": "VIEWER"}
WEB_ORIGINS = (
    "http://localhost:5173",
    "http://127.0.0.1:5173",
    "http://localhost:4173",
    "http://127.0.0.1:4173",
)


def private_write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    descriptor = os.open(path, os.O_CREAT | os.O_WRONLY | os.O_TRUNC, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as output:
        output.write(content)
    path.chmod(0o600)


def app_mappers() -> list:
    """Put realm roles in a flat `roles` claim and the API audience in access tokens."""
    return [
        {
            "name": "application-roles",
            "protocol": "openid-connect",
            "protocolMapper": "oidc-usermodel-realm-role-mapper",
            "config": {
                "claim.name": "roles",
                "jsonType.label": "String",
                "multivalued": "true",
                "access.token.claim": "true",
                "id.token.claim": "true",
                "userinfo.token.claim": "true",
            },
        },
        {
            "name": "application-audience",
            "protocol": "openid-connect",
            "protocolMapper": "oidc-audience-mapper",
            "config": {
                "included.custom.audience": AUDIENCE,
                "access.token.claim": "true",
                "id.token.claim": "false",
            },
        },
    ]


def realm(accounts: dict) -> dict:
    return {
        "realm": "nla",
        "displayName": "Text-to-SQL",
        "enabled": True,
        "sslRequired": "none",
        "registrationAllowed": False,
        "resetPasswordAllowed": False,
        "bruteForceProtected": True,
        "accessTokenLifespan": 300,
        "ssoSessionIdleTimeout": 1800,
        "roles": {"realm": [{"name": role} for role in ACCOUNT_ROLES.values()]},
        "clients": [
            {
                # Browser app: Authorization Code + PKCE, no client secret.
                "clientId": WEB_CLIENT,
                "name": "Text-to-SQL web application",
                "enabled": True,
                "protocol": "openid-connect",
                "publicClient": True,
                "standardFlowEnabled": True,
                "implicitFlowEnabled": False,
                "directAccessGrantsEnabled": False,
                "serviceAccountsEnabled": False,
                "redirectUris": [
                    f"{origin}/auth/{callback}"
                    for origin in WEB_ORIGINS
                    for callback in ("callback", "silent-callback")
                ],
                "webOrigins": list(WEB_ORIGINS),
                "attributes": {
                    "pkce.code.challenge.method": "S256",
                    "post.logout.redirect.uris": "##".join(WEB_ORIGINS),
                },
                "defaultClientScopes": ["basic", "web-origins", "profile", "email"],
                "protocolMappers": app_mappers(),
            },
            {
                # Local-only client so developers can fetch a token from the terminal to call
                # the API before the web login exists. It exists only in this generated realm.
                "clientId": CLI_CLIENT,
                "name": "Local developer CLI (testing only)",
                "enabled": True,
                "protocol": "openid-connect",
                "publicClient": True,
                "standardFlowEnabled": False,
                "implicitFlowEnabled": False,
                "directAccessGrantsEnabled": True,
                "serviceAccountsEnabled": False,
                "defaultClientScopes": ["basic", "profile", "email"],
                "protocolMappers": app_mappers(),
            },
        ],
        "users": [
            {
                # Fixed IDs let the backend grant warehouse access to these subjects up front.
                "id": account["id"],
                "username": username,
                "firstName": "Demo",
                "lastName": ACCOUNT_ROLES[username].title(),
                "email": f"{username}@local.invalid",
                "emailVerified": True,
                "enabled": True,
                "realmRoles": [ACCOUNT_ROLES[username]],
                "credentials": [
                    {"type": "password", "value": account["password"], "temporary": False}
                ],
            }
            for username, account in accounts["users"].items()
        ],
    }


def load_accounts() -> dict:
    try:
        accounts = json.loads(ACCOUNTS.read_text(encoding="utf-8"))
        entries = [accounts["keycloakAdmin"], *[accounts["users"][u] for u in ACCOUNT_ROLES]]
        if not all(len(entry["password"]) >= 32 for entry in entries):
            raise ValueError("password too short")
        for username in ACCOUNT_ROLES:
            uuid.UUID(accounts["users"][username]["id"])
        return accounts
    except (OSError, ValueError, KeyError, TypeError) as error:
        raise SystemExit(
            "Local Keycloak accounts file is invalid; restore .local/oidc/accounts.json"
        ) from error


def initialize() -> None:
    CONFIG.mkdir(parents=True, exist_ok=True, mode=0o700)
    if ACCOUNTS.exists():
        accounts = load_accounts()
    else:
        accounts = {
            "keycloakAdmin": {"username": "kc-admin", "password": secrets.token_urlsafe(32)},
            "users": {
                username: {"id": str(uuid.uuid4()), "password": secrets.token_urlsafe(32)}
                for username in ACCOUNT_ROLES
            },
        }
        private_write(ACCOUNTS, json.dumps(accounts, indent=2) + "\n")
    private_write(CONFIG / "import" / "nla-realm.json", json.dumps(realm(accounts), indent=2))
    private_write(
        CONFIG / "compose.env",
        f"NLA_IDP_ADMIN_USERNAME={accounts['keycloakAdmin']['username']}\n"
        f"NLA_IDP_ADMIN_PASSWORD={accounts['keycloakAdmin']['password']}\n",
    )
    # Read by the backend (spring.config.import): issuer/audience and local warehouse grants.
    private_write(
        CONFIG / "backend.env",
        f"OIDC_ISSUER_URI={ISSUER}\n"
        f"OIDC_AUDIENCE={AUDIENCE}\n"
        f"NLA_LOCAL_ANALYST_SUBJECT={accounts['users']['analyst']['id']}\n"
        f"NLA_LOCAL_VIEWER_SUBJECT={accounts['users']['viewer']['id']}\n",
    )
    print("Keycloak realm ready under .local/oidc/ (existing passwords preserved).")
    print("Accounts: admin, analyst, viewer; passwords are in .local/oidc/accounts.json")


def discovery() -> dict:
    url = f"{ISSUER}/.well-known/openid-configuration"
    try:
        with urllib.request.urlopen(url, timeout=5) as response:
            document = json.load(response)
    except (urllib.error.URLError, TimeoutError) as error:
        raise SystemExit("Keycloak is not reachable at " + ISSUER) from error
    if document.get("issuer") != ISSUER:
        raise SystemExit("Keycloak returned an unexpected issuer")
    return {"status": "UP", "issuer": ISSUER, "webClient": WEB_CLIENT, "audience": AUDIENCE}


def token(username: str) -> str:
    if username not in ACCOUNT_ROLES:
        raise SystemExit("Unknown account; use one of: " + ", ".join(ACCOUNT_ROLES))
    accounts = load_accounts()
    body = urllib.parse.urlencode(
        {
            "grant_type": "password",
            "client_id": CLI_CLIENT,
            "username": username,
            "password": accounts["users"][username]["password"],
            "scope": "openid",
        }
    ).encode()
    request = urllib.request.Request(
        f"{ISSUER}/protocol/openid-connect/token", data=body, method="POST"
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            return json.load(response)["access_token"]
    except urllib.error.HTTPError as error:
        raise SystemExit(f"Keycloak refused the token request (HTTP {error.code})") from error
    except urllib.error.URLError as error:
        raise SystemExit("Keycloak is not reachable at " + ISSUER) from error


def compose(*args: str) -> None:
    command = [
        "docker",
        "compose",
        "--env-file",
        str(CONFIG / "compose.env"),
        "-f",
        str(ROOT / "infra" / "compose.auth.yml"),
        *args,
    ]
    subprocess.run(command, cwd=ROOT, check=True)


def main() -> None:
    parser = argparse.ArgumentParser(description="Local Keycloak for Text-to-SQL")
    parser.add_argument("action", choices=("init", "up", "stop", "status", "check", "token"))
    parser.add_argument("username", nargs="?", help="account for the token action")
    args = parser.parse_args()
    if args.action == "init":
        initialize()
        return
    if not (CONFIG / "compose.env").is_file():
        raise SystemExit("Run: python infra/local/oidc.py init")
    if args.action == "check":
        print(json.dumps(discovery()))
    elif args.action == "token":
        print(token(args.username or ""))
    elif args.action == "up":
        compose("up", "-d", "--wait")
    elif args.action == "stop":
        compose("stop")
    else:
        compose("ps")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        print(f"docker compose failed with exit code {error.returncode}", file=sys.stderr)
        sys.exit(1)
