#!/bin/bash
# Microsoft OAuth flow for Minecraft authentication
set -euo pipefail

REFRESH_TOKEN="${1:-}"

if [ -z "$REFRESH_TOKEN" ]; then
    echo "{\"error\": \"No refresh token provided\"}" >&2
    exit 1
fi

# Step 1: Get Microsoft access token from refresh token
echo "[Auth] Exchanging refresh token for access token..." >&2
MSA_RESPONSE=$(curl -s -X POST "https://login.live.com/oauth20_token.srf" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "client_id=00000000402b5328" \
    -d "grant_type=refresh_token" \
    -d "refresh_token=$REFRESH_TOKEN" \
    -d "scope=service::user.auth.xboxlive.com::MBI_SSL")

MSA_ACCESS_TOKEN=$(echo "$MSA_RESPONSE" | jq -r '.access_token // empty')
if [ -z "$MSA_ACCESS_TOKEN" ] || [ "$MSA_ACCESS_TOKEN" = "null" ]; then
    echo "[Auth] ERROR: Failed to get Microsoft access token" >&2
    echo "$MSA_RESPONSE" | jq . >&2
    exit 1
fi
echo "[Auth] Got Microsoft access token" >&2

# Step 2: Authenticate with Xbox Live
echo "[Auth] Authenticating with Xbox Live..." >&2
echo "[Auth] DEBUG: MSA token length: ${#MSA_ACCESS_TOKEN}" >&2

# Build JSON payload using jq to ensure proper escaping
# NOTE: RpsTicket should NOT have "d=" prefix - that was causing HTTP 400
XBL_PAYLOAD=$(jq -n \
    --arg token "$MSA_ACCESS_TOKEN" \
    '{
        "Properties": {
            "AuthMethod": "RPS",
            "SiteName": "user.auth.xboxlive.com",
            "RpsTicket": $token
        },
        "RelyingParty": "http://auth.xboxlive.com",
        "TokenType": "JWT"
    }')

echo "[Auth] DEBUG: Payload length: ${#XBL_PAYLOAD}" >&2

XBL_RESPONSE=$(curl -s -w "\nHTTP_CODE:%{http_code}" -X POST "https://user.auth.xboxlive.com/user/authenticate" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json" \
    -d "$XBL_PAYLOAD")

# Extract HTTP code
HTTP_CODE=$(echo "$XBL_RESPONSE" | grep -o "HTTP_CODE:[0-9]*" | cut -d: -f2)
XBL_RESPONSE_BODY=$(echo "$XBL_RESPONSE" | sed 's/HTTP_CODE:[0-9]*$//')

echo "[Auth] DEBUG: HTTP Status: $HTTP_CODE" >&2
echo "[Auth] DEBUG: Response body length: ${#XBL_RESPONSE_BODY}" >&2

XBL_TOKEN=$(echo "$XBL_RESPONSE_BODY" | jq -r '.Token // empty')
USER_HASH=$(echo "$XBL_RESPONSE_BODY" | jq -r '.DisplayClaims.xui[0].uhs // empty')
if [ -z "$XBL_TOKEN" ] || [ "$XBL_TOKEN" = "null" ]; then
    echo "[Auth] ERROR: Failed to get Xbox Live token" >&2
    echo "[Auth] Xbox Live Response (HTTP $HTTP_CODE):" >&2
    echo "$XBL_RESPONSE_BODY" | jq -C . >&2 2>/dev/null || echo "$XBL_RESPONSE_BODY" >&2

    # Check if it's a token expiry issue
    ERROR_CODE=$(echo "$XBL_RESPONSE_BODY" | jq -r '.XErr // empty')
    if [ -n "$ERROR_CODE" ]; then
        echo "[Auth] Error code: $ERROR_CODE" >&2
    fi
    exit 1
fi
echo "[Auth] Got Xbox Live token" >&2

# Step 3: Get XSTS token
echo "[Auth] Getting XSTS token..." >&2
XSTS_RESPONSE=$(curl -s -X POST "https://xsts.auth.xboxlive.com/xsts/authorize" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json" \
    -d "{\"Properties\":{\"SandboxId\":\"RETAIL\",\"UserTokens\":[\"$XBL_TOKEN\"]},\"RelyingParty\":\"rp://api.minecraftservices.com/\",\"TokenType\":\"JWT\"}")

XSTS_TOKEN=$(echo "$XSTS_RESPONSE" | jq -r '.Token // empty')
if [ -z "$XSTS_TOKEN" ] || [ "$XSTS_TOKEN" = "null" ]; then
    echo "[Auth] ERROR: Failed to get XSTS token" >&2
    echo "$XSTS_RESPONSE" | jq . >&2
    exit 1
fi
echo "[Auth] Got XSTS token" >&2

# Step 4: Get Minecraft access token
echo "[Auth] Getting Minecraft access token..." >&2
MC_RESPONSE=$(curl -s -X POST "https://api.minecraftservices.com/authentication/login_with_xbox" \
    -H "Content-Type: application/json" \
    -H "Accept: application/json" \
    -d "{\"identityToken\": \"XBL3.0 x=$USER_HASH;$XSTS_TOKEN\"}")

MC_ACCESS_TOKEN=$(echo "$MC_RESPONSE" | jq -r '.access_token // empty')
if [ -z "$MC_ACCESS_TOKEN" ] || [ "$MC_ACCESS_TOKEN" = "null" ]; then
    echo "[Auth] ERROR: Failed to get Minecraft access token" >&2
    echo "$MC_RESPONSE" | jq . >&2
    exit 1
fi
echo "[Auth] Got Minecraft access token" >&2

# Step 5: Get Minecraft profile (username and UUID)
echo "[Auth] Getting Minecraft profile..." >&2
PROFILE_RESPONSE=$(curl -s -X GET "https://api.minecraftservices.com/minecraft/profile" \
    -H "Authorization: Bearer $MC_ACCESS_TOKEN")

MC_USERNAME=$(echo "$PROFILE_RESPONSE" | jq -r '.name // empty')
MC_UUID=$(echo "$PROFILE_RESPONSE" | jq -r '.id // empty')

if [ -z "$MC_USERNAME" ] || [ "$MC_USERNAME" = "null" ]; then
    echo "[Auth] ERROR: Failed to get Minecraft profile" >&2
    echo "$PROFILE_RESPONSE" | jq . >&2
    exit 1
fi

echo "[Auth] Successfully authenticated as $MC_USERNAME (UUID: $MC_UUID)" >&2

# Output JSON with credentials
jq -n \
    --arg username "$MC_USERNAME" \
    --arg uuid "$MC_UUID" \
    --arg token "$MC_ACCESS_TOKEN" \
    '{username: $username, uuid: $uuid, accessToken: $token}'
