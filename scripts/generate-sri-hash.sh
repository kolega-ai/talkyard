#!/bin/bash
#
# Generate SRI hash for external resources
# Usage: ./generate-sri-hash.sh <URL>
# 
# Example:
#   ./generate-sri-hash.sh https://cdnjs.cloudflare.com/ajax/libs/core-js/2.6.12/core.min.js
#

set -euo pipefail

if [ $# -ne 1 ]; then
    echo "Usage: $0 <URL>"
    echo ""
    echo "Generate Subresource Integrity hash for an external script"
    echo ""
    echo "Examples:"
    echo "  $0 https://cdnjs.cloudflare.com/ajax/libs/core-js/2.6.12/core.min.js"
    echo "  $0 https://cdn.example.com/lib/1.0.0/lib.min.js"
    echo ""
    exit 1
fi

URL="$1"

echo "Generating SRI hash for: $URL"
echo ""

# Validate URL
if [[ ! "$URL" =~ ^https:// ]]; then
    echo "Error: URL must use HTTPS for security"
    exit 1
fi

# Download and generate hash
echo "Downloading and generating SHA-384 hash..."
HASH=$(curl -sL "$URL" | openssl dgst -sha384 -binary | openssl base64 -A)

if [ $? -eq 0 ] && [ -n "$HASH" ]; then
    echo "✅ Success!"
    echo ""
    echo "SRI Hash: sha384-$HASH"
    echo ""
    echo "HTML usage:"
    echo "  <script src=\"$URL\" integrity=\"sha384-$HASH\" crossorigin=\"anonymous\"></script>"
    echo ""
    echo "Scala ExternalResource:"
    echo "  val Resource = ExternalResource("
    echo "    url = \"$URL\","
    echo "    integrity = \"sha384-$HASH\","
    echo "    lastVerified = \"$(date +%Y-%m-%d)\","
    echo "    notes = \"[DESCRIPTION]\""
    echo "  )"
    echo ""
    echo "⚠️  SECURITY REMINDER:"
    echo "   1. Manually inspect the downloaded script for malicious content"
    echo "   2. Verify this is the expected/official version"
    echo "   3. Test thoroughly in staging before production deployment"
    echo "   4. Update the lastVerified date in ExternalResourceIntegrity.scala"
else
    echo "❌ Failed to download or generate hash"
    exit 1
fi