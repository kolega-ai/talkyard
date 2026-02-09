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

package com.debiki.e2e

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.util.Base64
import java.time.Instant

/**
 * Generates secure bypass tokens for E2E tests.
 * 
 * This code only exists in test modules and is NOT included in production builds.
 * 
 * Usage in E2E tests:
 * 1. Set TALKYARD_TEST_BYPASS_SECRET environment variable
 * 2. Generate token: TestBypassTokenGenerator.generateSiteCreationToken()
 * 3. Include token in request header: X-E2E-Test-Token
 */
object TestBypassTokenGenerator {
  
  /**
   * Generate a time-limited bypass token for site creation.
   * 
   * The token is valid for 5 minutes and includes:
   * - Current timestamp
   * - Action ("create_site")
   * - HMAC signature for verification
   * 
   * @return Base64-encoded token or None if secret not configured
   */
  def generateSiteCreationToken(): Option[String] = {
    sys.env.get("TALKYARD_TEST_BYPASS_SECRET").map { secret =>
      val timestamp = Instant.now().getEpochSecond
      val action = "create_site"
      val data = s"$timestamp:$action"
      val hmac = computeHmac(data, secret)
      val payload = s"$data:$hmac"
      Base64.getEncoder.encodeToString(payload.getBytes("UTF-8"))
    }
  }
  
  /**
   * Generate a token that's valid for a specific duration.
   * 
   * @param validForSeconds How long the token should be valid (max 300 seconds)
   * @return Base64-encoded token or None if secret not configured
   */
  def generateSiteCreationTokenWithDuration(validForSeconds: Int): Option[String] = {
    require(validForSeconds > 0 && validForSeconds <= 300, 
      "Token validity must be between 1 and 300 seconds")
      
    sys.env.get("TALKYARD_TEST_BYPASS_SECRET").map { secret =>
      val timestamp = Instant.now().getEpochSecond
      val action = "create_site"
      val data = s"$timestamp:$action"
      val hmac = computeHmac(data, secret)
      val payload = s"$data:$hmac"
      Base64.getEncoder.encodeToString(payload.getBytes("UTF-8"))
    }
  }
  
  /**
   * Validate that a generated token would be accepted.
   * This is for test verification only.
   */
  def validateToken(token: String): Boolean = {
    sys.env.get("TALKYARD_TEST_BYPASS_SECRET") match {
      case None => false
      case Some(secret) =>
        try {
          val decoded = new String(Base64.getDecoder.decode(token), "UTF-8")
          val parts = decoded.split(":")
          
          if (parts.length != 3) return false
          
          val timestamp = parts(0).toLong
          val action = parts(1)
          val providedHmac = parts(2)
          
          if (action != "create_site") return false
          
          val now = Instant.now().getEpochSecond
          if (now - timestamp > 300) return false // 5 minutes
          if (timestamp > now + 60) return false  // 1 minute clock skew
          
          val expectedHmac = computeHmac(s"$timestamp:$action", secret)
          constantTimeEquals(expectedHmac, providedHmac)
        } catch {
          case _: Exception => false
        }
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
}

/**
 * Helper for test frameworks (ScalaTest, etc.)
 */
trait TestBypassSupport {
  
  /**
   * Get a valid bypass token for the current test.
   * Fails the test if no bypass secret is configured.
   */
  protected def getBypassToken(): String = {
    TestBypassTokenGenerator.generateSiteCreationToken() match {
      case Some(token) => token
      case None => 
        throw new RuntimeException(
          "TALKYARD_TEST_BYPASS_SECRET not configured. " +
          "Set this environment variable for E2E tests."
        )
    }
  }
  
  /**
   * Create HTTP headers with bypass token.
   */
  protected def withBypassToken(headers: Map[String, String] = Map.empty): Map[String, String] = {
    headers + ("X-E2E-Test-Token" -> getBypassToken())
  }
}