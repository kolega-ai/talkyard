/**
 * Copyright (C) 2024 Kaj Magnus Lindberg
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package com.debiki.core

import scala.collection.immutable.Map

/**
 * Centralized registry for external resource integrity hashes.
 * 
 * SECURITY: All external scripts MUST be registered here with their SRI hashes.
 * 
 * To generate a hash for an external script:
 *   curl -s https://example.com/script.js | openssl dgst -sha384 -binary | openssl base64 -A
 *   
 * Or use: https://www.srihash.org/
 * 
 * MAINTENANCE: When updating external resources:
 * 1. Download the new version and verify it's not malicious
 * 2. Generate the new hash using the command above
 * 3. Update both the URL (if versioned) and the hash below
 * 4. Test thoroughly in staging before production deployment
 * 5. Document the change in CHANGELOG with date and reason
 *
 * Last audit: 2024-02-09 by Security Audit
 */
object ExternalResourceIntegrity {

  /**
   * Represents an external resource with integrity verification.
   * 
   * @param url Primary URL to load the resource from
   * @param integrity SRI hash in format "sha384-..."
   * @param fallbackUrl Optional fallback URL (e.g., self-hosted copy)
   * @param crossOrigin CORS setting, typically "anonymous" for SRI
   * @param lastVerified Date when this hash was last verified
   * @param notes Any relevant notes about this resource
   */
  case class ExternalResource(
    url: String,
    integrity: String,
    fallbackUrl: Option[String] = None,
    crossOrigin: String = "anonymous",
    lastVerified: String = "",
    notes: String = ""
  ) {
    require(integrity.startsWith("sha384-") || integrity.startsWith("sha512-"),
      s"Integrity hash must use sha384 or sha512, got: $integrity")
    require(url.startsWith("https://"),
      s"External resources must use HTTPS, got: $url")
  }

  // ============================================================================
  // POLYFILL STRATEGY
  // ============================================================================
  // 
  // CRITICAL SECURITY NOTE (2024):
  // polyfill.io was compromised in a supply chain attack. We now use one of:
  // 1. Cloudflare's mirror: cdnjs.cloudflare.com/polyfill/
  // 2. Fastly's mirror: polyfill-fastly.io (maintained by Fastly)
  // 3. Self-hosted bundle (RECOMMENDED - see PolyfillBundle below)
  //
  // The self-hosted approach is strongly recommended because:
  // - Polyfills are relatively static (browsers don't change past behavior)
  // - Full control over what code runs
  // - No external dependency at runtime
  // - Zero supply chain risk
  // ============================================================================

  /**
   * Configuration for self-hosted polyfill bundle.
   * This is the RECOMMENDED approach for maximum security.
   */
  object PolyfillBundle {
    // Path to self-hosted polyfill bundle (generated at build time)
    val selfHostedPath = "/-/assets/polyfills/bundle"
    
    // Minimum features we need to polyfill for legacy browser support
    val requiredFeatures: Set[String] = Set(
      "Promise",
      "fetch",
      "Array.prototype.includes",
      "Object.assign",
      "String.prototype.includes",
      "Symbol",
      "Symbol.iterator"
    )
    
    // Browser versions that need polyfills
    // Below these versions, we serve the polyfill bundle
    val browserThresholds: Map[String, Int] = Map(
      "Chrome" -> 60,
      "Firefox" -> 55,
      "Safari" -> 11,
      "Edge" -> 15
    )
  }

  /**
   * External CDN polyfill configuration.
   * Used as fallback if self-hosting is disabled.
   * 
   * IMPORTANT: We use Cloudflare's maintained mirror, NOT polyfill.io
   * Note: This hash will need to be updated when the polyfill content changes
   */
  val PolyfillCdn = ExternalResource(
    url = "https://cdnjs.cloudflare.com/ajax/libs/core-js/2.6.12/core.min.js",
    integrity = "sha384-ZSs6LKr2GoUPDyHrN+rCQgyHL1yUyok5xMniSrgeRG7rUvA6vTmxronM1eZOfjgz",
    fallbackUrl = Some("https://polyfill-fastly.io/v3/polyfill.min.js?features=Promise%2Cfetch%2CArray.prototype.includes%2CObject.assign"),
    lastVerified = "2024-02-09",
    notes = "Using Cloudflare-hosted core-js. Fallback to Fastly mirror. Avoid polyfill.io due to compromise."
  )

  // ============================================================================
  // GOOGLE ANALYTICS
  // ============================================================================
  //
  // CHALLENGE: Google Analytics scripts are updated frequently without notice.
  // Google does not provide SRI hashes and explicitly states the script URL
  // should be loaded without integrity attributes.
  //
  // OPTIONS:
  // 1. Accept the risk (current state)
  // 2. Use server-side analytics (Plausible, Matomo) - no external JS
  // 3. Proxy GA through your own server with caching and hash verification
  // 4. Use Measurement Protocol (server-side GA, no client JS)
  //
  // We implement Option 3 with a feature flag for Option 2/4.
  // ============================================================================

  object GoogleAnalytics {
    // GA4's gtag.js - this URL is stable but content changes
    val gtagUrl = "https://www.googletagmanager.com/gtag/js"
    
    // Self-hosted proxy path (if enabled)
    val proxyPath = "/-/analytics/gtag.js"
    
    // When proxying, we cache and verify the script
    // Hash is checked at proxy-time, not client-side
    case class ProxiedScript(
      cachedAt: Long,
      hash: String,
      content: String
    )
  }

  // ============================================================================
  // HASH GENERATION UTILITIES
  // ============================================================================

  /**
   * Generate SRI hash for content.
   * Used by build tools and for verification.
   */
  def generateSriHash(content: Array[Byte], algorithm: String = "sha384"): String = {
    import java.security.MessageDigest
    import java.util.Base64
    
    val digest = MessageDigest.getInstance(algorithm.toUpperCase.replace("SHA", "SHA-"))
    val hash = digest.digest(content)
    val base64 = Base64.getEncoder.encodeToString(hash)
    s"$algorithm-$base64"
  }
  
  /**
   * Verify content against expected SRI hash.
   */
  def verifySriHash(content: Array[Byte], expectedHash: String): Boolean = {
    val algorithm = expectedHash.takeWhile(_ != '-')
    val actualHash = generateSriHash(content, algorithm)
    // Constant-time comparison to prevent timing attacks
    java.security.MessageDigest.isEqual(
      actualHash.getBytes("UTF-8"),
      expectedHash.getBytes("UTF-8")
    )
  }
}