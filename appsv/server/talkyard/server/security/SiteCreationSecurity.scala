/**
 * Copyright (C) 2023 Kaj Magnus Lindberg
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

package talkyard.server.security

import com.debiki.core._
import com.debiki.core.Prelude._
import play.api.mvc.RequestHeader
import play.api.{Logger, Configuration}
import javax.inject.{Inject, Singleton}
import java.time.Instant
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Try, Success, Failure}

/**
 * Enhanced security for site creation with defense-in-depth approach.
 * 
 * Key improvements:
 * 1. Composite identity tracking (IP + browser fingerprint + email domain)
 * 2. Secured test bypass mechanism with time-limited tokens
 * 3. Risk-based rate limiting
 * 4. Comprehensive audit logging
 */
@Singleton
class SiteCreationSecurityService @Inject()(
  config: Configuration,
  auditLogger: SecurityAuditLogger
)(implicit ec: ExecutionContext) {

  private val logger = Logger(this.getClass)
  
  /**
   * Enhanced identity tracking that combines multiple signals
   * to make abuse significantly harder.
   */
  case class CompositeIdentity(
    ipAddress: IpAddress,
    ipSubnet: String,
    browserFingerprint: Option[String],
    emailDomain: Option[String],
    userAgentHash: String,
    createdAt: Instant
  ) {
    
    /**
     * Generate multiple rate limit keys for different levels of tracking.
     * We check against ALL keys - if any limit is exceeded, block the request.
     */
    def rateLimitKeys: Seq[RateLimitKey] = {
      val keys = Seq.newBuilder[RateLimitKey]
      
      // Primary key: IP + fingerprint (strongest signal)
      keys += RateLimitKey(
        key = s"create_site:primary:${ipAddress.value}:${browserFingerprint.getOrElse("none")}",
        weight = 1.0,
        description = "IP + Browser fingerprint"
      )
      
      // Network block (catches simple IP rotation)
      keys += RateLimitKey(
        key = s"create_site:subnet:$ipSubnet",
        weight = 0.4, // More lenient for shared networks
        description = "IP subnet"
      )
      
      // Browser fingerprint across IPs (catches IP rotation)
      browserFingerprint.foreach { fp =>
        keys += RateLimitKey(
          key = s"create_site:fingerprint:$fp",
          weight = 0.7,
          description = "Browser fingerprint"
        )
      }
      
      // Email domain (catches bulk signups from same domain)
      emailDomain.foreach { domain =>
        keys += RateLimitKey(
          key = s"create_site:email_domain:$domain",
          weight = 0.3, // Very lenient for legitimate domains like gmail.com
          description = "Email domain"
        )
      }
      
      // User agent pattern (catches automated tools)
      keys += RateLimitKey(
        key = s"create_site:user_agent:$userAgentHash",
        weight = 0.2,
        description = "User agent pattern"
      )
      
      keys.result()
    }
  }
  
  case class RateLimitKey(
    key: String,
    weight: Double,
    description: String
  )
  
  /**
   * Enhanced rate limiting with multiple time windows and composite tracking.
   */
  object EnhancedRateLimits {
    // Stricter limits than the current CreateSite limits
    val TimeWindows = Seq(
      (15, 1),    // 1 per 15 seconds (vs current 2)
      (900, 3),   // 3 per 15 minutes (vs current 5) 
      (3600, 6),  // 6 per hour (new)
      (86400, 12) // 12 per day (vs current 10, but composite tracking makes it stricter)
    )
    
    // High-risk IPs get even stricter limits
    val HighRiskTimeWindows = Seq(
      (15, 1),
      (900, 1),
      (3600, 2),
      (86400, 3)
    )
  }
  
  /**
   * Extract composite identity from request with enhanced fingerprinting.
   */
  def extractCompositeIdentity(
    request: RequestHeader, 
    emailOpt: Option[String]
  ): CompositeIdentity = {
    
    val ip = IpAddress(request.remoteAddress)
    val subnet = calculateSubnet(ip)
    val fingerprint = extractBrowserFingerprint(request)
    val emailDomain = emailOpt.flatMap(extractEmailDomain)
    val userAgentHash = hashUserAgent(request)
    
    CompositeIdentity(
      ipAddress = ip,
      ipSubnet = subnet,
      browserFingerprint = fingerprint,
      emailDomain = emailDomain,
      userAgentHash = userAgentHash,
      createdAt = Instant.now()
    )
  }
  
  /**
   * Calculate network subnet for rate limiting.
   * Uses /24 for IPv4 and /48 for IPv6.
   */
  private def calculateSubnet(ip: IpAddress): String = {
    val addr = ip.value
    if (addr.contains(":")) {
      // IPv6: use /48 (site allocation)
      addr.split(":").take(3).mkString(":") + "::/48"
    } else {
      // IPv4: use /24 (typical ISP allocation)
      addr.split("\\.").take(3).mkString(".") + ".0/24"
    }
  }
  
  /**
   * Extract browser fingerprint from request headers.
   * Combines multiple signals for better tracking.
   */
  private def extractBrowserFingerprint(request: RequestHeader): Option[String] = {
    // Client-side fingerprint (recommended: use FingerprintJS or similar)
    val clientFingerprint = request.headers.get("X-Browser-Fingerprint")
    
    if (clientFingerprint.isDefined) {
      clientFingerprint
    } else {
      // Fallback: server-side fingerprint from headers
      val components = Seq(
        request.headers.get("User-Agent"),
        request.headers.get("Accept-Language"),
        request.headers.get("Accept-Encoding"),
        request.headers.get("Accept"),
        request.headers.get("DNT"),
        // Screen resolution if provided by client
        request.headers.get("X-Screen-Resolution")
      ).flatten
      
      if (components.length >= 2) {
        Some(hashComponents(components))
      } else {
        None
      }
    }
  }
  
  private def extractEmailDomain(email: String): Option[String] = {
    email.split("@").lastOption.map(_.toLowerCase.trim).filter(_.nonEmpty)
  }
  
  private def hashUserAgent(request: RequestHeader): String = {
    val ua = request.headers.get("User-Agent").getOrElse("unknown")
    // Normalize to browser family + major version to catch automated patterns
    val normalized = normalizeUserAgent(ua)
    hashString(normalized).take(12) // First 12 chars for brevity
  }
  
  private def normalizeUserAgent(userAgent: String): String = {
    val ua = userAgent.toLowerCase
    
    // Detect automation tools
    if (ua.contains("curl") || ua.contains("wget") || ua.contains("python") ||
        ua.contains("httpx") || ua.contains("requests")) {
      return "automated_tool"
    }
    
    // Simplified browser detection
    if (ua.contains("chrome")) {
      "chrome_" + extractMajorVersion(ua, "chrome")
    } else if (ua.contains("firefox")) {
      "firefox_" + extractMajorVersion(ua, "firefox")
    } else if (ua.contains("safari") && !ua.contains("chrome")) {
      "safari_" + extractMajorVersion(ua, "safari")
    } else {
      "other_browser"
    }
  }
  
  private def extractMajorVersion(ua: String, browser: String): String = {
    val pattern = s"$browser[/\\s](\\d+)".r
    pattern.findFirstMatchIn(ua).map(_.group(1)).getOrElse("unknown")
  }
  
  private def hashComponents(components: Seq[String]): String = {
    hashString(components.mkString("|"))
  }
  
  private def hashString(input: String): String = {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(input.getBytes("UTF-8"))
    hash.take(16).map("%02x".format(_)).mkString
  }
  
  /**
   * Check if IP shows signs of being high-risk (VPN, datacenter, etc.)
   * This is a simplified implementation - in production you'd use
   * services like MaxMind, IPQualityScore, etc.
   */
  def assessIpRisk(ip: IpAddress): Future[IpRiskAssessment] = {
    Future.successful {
      val ipStr = ip.value
      var riskScore = 0.0
      val riskFactors = Seq.newBuilder[String]
      
      // Simple heuristics - replace with real IP intelligence
      
      // Check for common VPN/hosting ranges (simplified)
      if (isCommonHostingRange(ipStr)) {
        riskScore += 0.4
        riskFactors += "Hosting/datacenter IP range"
      }
      
      // Check for Tor exit nodes (would need real-time list)
      // if (isTorExitNode(ipStr)) {
      //   riskScore += 0.6
      //   riskFactors += "Tor exit node"
      // }
      
      // Check for residential vs business IP characteristics
      if (hasDatacenterCharacteristics(ipStr)) {
        riskScore += 0.3
        riskFactors += "Datacenter characteristics"
      }
      
      IpRiskAssessment(
        score = riskScore.min(1.0),
        factors = riskFactors.result(),
        isHighRisk = riskScore > 0.5
      )
    }
  }
  
  private def isCommonHostingRange(ip: String): Boolean = {
    // Very basic implementation - in production use proper ASN/hosting detection
    val octets = ip.split("\\.")
    if (octets.length == 4) {
      Try {
        val first = octets(0).toInt
        val second = octets(1).toInt
        
        // Common AWS, Google Cloud, Azure ranges (simplified)
        (first == 3 && second >= 208 && second <= 255) || // AWS
        (first == 34 && second >= 64 && second <= 127) ||  // Google Cloud
        (first == 20) ||                                   // Microsoft Azure
        (first == 167 && second == 99)                     // DigitalOcean
      }.getOrElse(false)
    } else {
      false
    }
  }
  
  private def hasDatacenterCharacteristics(ip: String): Boolean = {
    // Heuristic: datacenter IPs often have specific patterns
    // This is very simplified - real implementation would use PTR records, 
    // ASN lookup, geolocation databases, etc.
    false // Placeholder
  }
  
  case class IpRiskAssessment(
    score: Double,
    factors: Seq[String],
    isHighRisk: Boolean
  )
  
  /**
   * Secured test bypass mechanism using HMAC-signed time-limited tokens.
   * Only works in non-production environments.
   */
  object SecureTestBypass {
    
    private val environment = config.get[String]("talkyard.environment")
    private val isProduction = environment == "production"
    
    // Test bypass secret from environment variable (never in config files)
    private val bypassSecret = config.getOptional[String]("talkyard.testBypass.secret")
      .orElse(sys.env.get("TALKYARD_TEST_BYPASS_SECRET"))
    
    private val TokenValiditySeconds = 300 // 5 minutes
    
    /**
     * Validate test bypass token from request.
     * Returns true only if valid token provided in non-production environment.
     */
    def validateBypass(
      request: RequestHeader,
      identity: CompositeIdentity
    ): Boolean = {
      
      // CRITICAL: Never allow bypass in production
      if (isProduction) {
        val token = request.headers.get("X-E2E-Test-Token")
        if (token.isDefined) {
          auditLogger.logCriticalSecurityEvent(
            event = "BYPASS_ATTEMPT_IN_PRODUCTION",
            ip = identity.ipAddress.value,
            details = Map("hasToken" -> "true")
          )
        }
        return false
      }
      
      // Check for bypass token
      request.headers.get("X-E2E-Test-Token") match {
        case None => false
        case Some(token) => validateToken(token, identity)
      }
    }
    
    private def validateToken(token: String, identity: CompositeIdentity): Boolean = {
      bypassSecret match {
        case None =>
          auditLogger.logSecurityEvent(
            event = "BYPASS_ATTEMPT_NO_SECRET",
            ip = identity.ipAddress.value,
            severity = SecuritySeverity.Warning
          )
          false
          
        case Some(secret) =>
          parseAndValidateToken(token, secret, identity)
      }
    }
    
    private def parseAndValidateToken(
      token: String, 
      secret: String, 
      identity: CompositeIdentity
    ): Boolean = {
      
      Try {
        // Token format: base64(timestamp:action:hmac)
        val decoded = new String(Base64.getDecoder.decode(token), "UTF-8")
        val parts = decoded.split(":")
        
        if (parts.length != 3) {
          throw new IllegalArgumentException("Invalid token format")
        }
        
        val timestamp = parts(0).toLong
        val action = parts(1)
        val providedHmac = parts(2)
        
        // Verify action
        if (action != "create_site") {
          throw new IllegalArgumentException(s"Invalid action: $action")
        }
        
        // Verify timestamp
        val now = Instant.now().getEpochSecond
        if (now - timestamp > TokenValiditySeconds) {
          throw new IllegalArgumentException("Token expired")
        }
        
        if (timestamp > now + 60) { // Allow 60s clock skew
          throw new IllegalArgumentException("Token from future")
        }
        
        // Verify HMAC
        val expectedHmac = computeHmac(s"$timestamp:$action", secret)
        if (!constantTimeEquals(expectedHmac, providedHmac)) {
          throw new IllegalArgumentException("Invalid HMAC")
        }
        
        auditLogger.logSecurityEvent(
          event = "VALID_TEST_BYPASS",
          ip = identity.ipAddress.value,
          severity = SecuritySeverity.Info,
          details = Map("action" -> action)
        )
        
        true
        
      } match {
        case Success(result) => result
        case Failure(ex) =>
          auditLogger.logSecurityEvent(
            event = "INVALID_TEST_BYPASS",
            ip = identity.ipAddress.value,
            severity = SecuritySeverity.Warning,
            details = Map("reason" -> ex.getMessage)
          )
          false
      }
    }
    
    private def computeHmac(data: String, secret: String): String = {
      val algorithm = "HmacSHA256"
      val mac = Mac.getInstance(algorithm)
      val keySpec = new SecretKeySpec(secret.getBytes("UTF-8"), algorithm)
      mac.init(keySpec)
      Base64.getUrlEncoder.withoutPadding.encodeToString(mac.doFinal(data.getBytes("UTF-8")))
    }
    
    private def constantTimeEquals(a: String, b: String): Boolean = {
      if (a.length != b.length) return false
      var result = 0
      for (i <- a.indices) {
        result |= a(i) ^ b(i)
      }
      result == 0
    }
    
    /**
     * Generate a test bypass token for E2E tests.
     * Should only be called by test infrastructure.
     */
    def generateTestToken(): Option[String] = {
      if (isProduction) return None
      
      bypassSecret.map { secret =>
        val timestamp = Instant.now().getEpochSecond
        val action = "create_site"
        val data = s"$timestamp:$action"
        val hmac = computeHmac(data, secret)
        val payload = s"$data:$hmac"
        Base64.getEncoder.encodeToString(payload.getBytes("UTF-8"))
      }
    }
  }
}

/**
 * Security audit logger for tracking site creation security events.
 */
@Singleton
class SecurityAuditLogger @Inject()()(implicit ec: ExecutionContext) {
  
  private val logger = Logger("security.audit")
  
  def logCriticalSecurityEvent(
    event: String,
    ip: String,
    details: Map[String, String] = Map.empty
  ): Unit = {
    val logData = Map(
      "event" -> event,
      "severity" -> "CRITICAL",
      "ip" -> ip,
      "timestamp" -> Instant.now().toString
    ) ++ details
    
    logger.error(s"[CRITICAL SECURITY EVENT] ${formatLogData(logData)}")
  }
  
  def logSecurityEvent(
    event: String,
    ip: String,
    severity: SecuritySeverity,
    details: Map[String, String] = Map.empty
  ): Unit = {
    val logData = Map(
      "event" -> event,
      "severity" -> severity.toString,
      "ip" -> ip,
      "timestamp" -> Instant.now().toString
    ) ++ details
    
    val message = s"[SECURITY] ${formatLogData(logData)}"
    
    severity match {
      case SecuritySeverity.Critical => logger.error(message)
      case SecuritySeverity.Warning => logger.warn(message)
      case SecuritySeverity.Info => logger.info(message)
    }
  }
  
  private def formatLogData(data: Map[String, String]): String = {
    data.map { case (k, v) => s"$k=$v" }.mkString(", ")
  }
}

sealed trait SecuritySeverity
object SecuritySeverity {
  case object Critical extends SecuritySeverity
  case object Warning extends SecuritySeverity  
  case object Info extends SecuritySeverity
}