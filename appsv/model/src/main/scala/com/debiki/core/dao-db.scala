/**
 * Copyright (C) 2012-2013 Kaj Magnus Lindberg (born 1979)
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

import com.lambdaworks.crypto.SCryptUtil
import Prelude._


/** Constructs database DAO:s, implemented by service providers,
  * (currently only debiki-dao-rdb, for Postgres) and used by debiki-server.
  */
abstract class DbDaoFactory {  CLEAN_UP; // Delete this class? And rename DbDao2 to DbTransactionFactory?

  def migrations: ScalaBasedDatabaseMigrations

  final def newDbDao2(): DbDao2 =
    new DbDao2(this)

  protected[core] def newSiteTransaction(siteId: SiteId, readOnly: Bo,
    mustBeSerializable: Bo): SiteTx

  protected[core] def newSystemTransaction(readOnly: Bo, allSitesWriteLocked: Bo): SysTx

}


object DbDao {

  case class SiteAlreadyExistsException(newSite: Site, message: String) extends QuickException

  case class TooManySitesCreatedByYouException(ip: String) extends QuickException {
    override def getMessage = "Website creation limit exceeded"
  }

  case object TooManySitesCreatedInTotalException extends QuickException

  case class EmailNotFoundException(emailId: String)
    extends RuntimeException("No email with id: "+ emailId)

  case class BadEmailTypeException(emailId: String)
    extends RuntimeException(s"Email with id $emailId has no recipient user id")

  case class EmailAddressChangedException(email: Email, user: Participant)
    extends QuickException

  case class DuplicateUsername(username: String) extends RuntimeException(s"Duplicate username: $username")
  case class DuplicateUserEmail(addr: String) extends RuntimeException(s"Duplicate user email: $addr")
  case object DuplicateGuest extends RuntimeException("Duplicate guest")

  object IdentityNotFoundException extends QuickMessageException("Identity not found")
  object NoSuchEmailOrUsernameException extends QuickMessageException("No user with that email or username")
  object EmailNotVerifiedException extends QuickMessageException("Email not verified")
  object MemberHasNoPasswordException extends QuickMessageException("User has no password")
  object BadPasswordException extends QuickMessageException("Bad password")
  object UserDeletedException extends QuickMessageException("User deleted")

  RENAME // to DuplicateActionEx?
  case object DuplicateVoteException extends RuntimeException("Duplicate vote")

  class PageNotFoundException(message: String) extends RuntimeException(message)

  case class PageNotFoundByIdException(
    tenantId: SiteId,
    pageId: PageId,
    details: Option[String] = None)
    extends PageNotFoundException(
      s"Found no page with id: $pageId, tenant id: $tenantId" +
        prettyDetails(details))

  case class PageNotFoundByPathException(
    pagePath: PagePath,
    details: Option[String] = None)
    extends PageNotFoundException(
      s"Found no page at ${pagePath.siteId}:${pagePath.value}" +
        prettyDetails(details))

  REFACTOR // don't throw, return errors instead so cannot forget to handle!
  @deprecated
  case class PathClashException(newPagePath: PagePathWithId)
    extends RuntimeException(s"newPagePath: $newPagePath, value: ${newPagePath.value}")

  case class BadPageRoleException(details: String)
    extends RuntimeException(details)

  private def prettyDetails(anyDetails: Option[String]) = anyDetails match {
    case None => ""
    case Some(message) => s", details: $message"
  }

  /** Password hash algorithm prefixes */
  val ScryptPrefix = "scrypt:"
  val ScryptV2Prefix = "scrypt2:"
  
  /** Test-only cleartext passwords - ONLY enabled in test environments */
  val CleartextPrefix = "cleartext:"

  // SCrypt parameters - upgraded to OWASP 2024 recommendations
  private val CurrentScryptN = 131072  // 2^17 (upgraded from 2^16)
  private val CurrentScryptR = 8
  private val CurrentScryptP = 1
  private val MinimumScryptN = 65536   // 2^16 (minimum for legacy hash acceptance)

  sealed trait PasswordCheckResult
  case object ValidPassword extends PasswordCheckResult
  case object InvalidPassword extends PasswordCheckResult
  case object ValidPasswordNeedsUpgrade extends PasswordCheckResult

  /**
   * Check password against hash with improved security and upgrade detection.
   * Addresses CWE-916 by using stronger parameters and eliminating timing vulnerabilities.
   */
  def checkPasswordWithUpgrade(plainTextPassword: String, hash: String): PasswordCheckResult = {
    if (hash.startsWith(ScryptV2Prefix)) {
      // New format with stronger parameters
      val hashNoPrefix = hash.drop(ScryptV2Prefix.length)
      if (SCryptUtil.check(plainTextPassword, hashNoPrefix)) ValidPassword
      else InvalidPassword
    }
    else if (hash.startsWith(ScryptPrefix)) {
      // Legacy format - needs upgrade but still acceptable
      val hashNoPrefix = hash.drop(ScryptPrefix.length)
      if (SCryptUtil.check(plainTextPassword, hashNoPrefix)) {
        // Password is valid but hash uses weaker parameters - signal upgrade needed
        ValidPasswordNeedsUpgrade
      } else {
        InvalidPassword
      }
    }
    else if (hash.startsWith(CleartextPrefix)) {
      // SECURITY: Only allow cleartext in test environments
      // Check environment to prevent accidental production use
      val isTestEnvironment = sys.env.getOrElse("ENVIRONMENT", "production") == "test" ||
                             sys.props.get("testing").contains("true")
      
      if (!isTestEnvironment) {
        // Log security incident - cleartext password in production
        System.err.println(s"SECURITY WARNING: Cleartext password attempted in production environment")
        InvalidPassword
      } else {
        val cleartext = hash.drop(CleartextPrefix.length)
        // Use constant-time comparison to prevent timing attacks
        if (constantTimeStringEquals(plainTextPassword, cleartext)) ValidPassword
        else InvalidPassword
      }
    }
    else if (!hash.contains(':')) {
      die("EsE2PUY8", s"No password algorithm prefix in password hash")
    }
    else {
      val prefix = hash.takeWhile(_ != ':')
      die("EsE4PKUY1", s"Unknown password algorithm: '$prefix'")
    }
  }

  /**
   * Enhanced password check with security improvements.
   * Maintains backward compatibility while addressing CWE-916 vulnerabilities.
   */
  def checkPassword(plainTextPassword: String, hash: String): Boolean = {
    if (hash.startsWith(ScryptV2Prefix)) {
      // New format with stronger parameters
      val hashNoPrefix = hash.drop(ScryptV2Prefix.length)
      SCryptUtil.check(plainTextPassword, hashNoPrefix)
    }
    else if (hash.startsWith(ScryptPrefix)) {
      // Legacy format - still acceptable for compatibility
      val hashNoPrefix = hash.drop(ScryptPrefix.length)
      SCryptUtil.check(plainTextPassword, hashNoPrefix)
    }
    else if (hash.startsWith(CleartextPrefix)) {
      // SECURITY: Only allow cleartext in test environments
      val isTestEnvironment = sys.env.getOrElse("ENVIRONMENT", "production") == "test" ||
                             sys.props.get("testing").contains("true")
      
      if (!isTestEnvironment) {
        // Log security incident and reject
        System.err.println(s"SECURITY WARNING: Cleartext password attempted in production environment")
        false
      } else {
        val cleartext = hash.drop(CleartextPrefix.length)
        // Use constant-time comparison to prevent timing attacks
        constantTimeStringEquals(plainTextPassword, cleartext)
      }
    }
    else if (!hash.contains(':')) {
      die("EsE2PUY8", s"No password algorithm prefix in password hash")
    }
    else {
      val prefix = hash.takeWhile(_ != ':')
      die("EsE4PKUY1", s"Unknown password algorithm: '$prefix'")
    }
  }

  /**
   * Create new password hash with strengthened SCrypt parameters.
   * Uses OWASP 2024 recommended parameters: n=131072, r=8, p=1
   */
  def saltAndHashPassword(plainTextPassword: String): String = {
    // Notes:
    // 1) In Dockerfile [30PUK42] Java has been configured to use /dev/urandom — otherwise,
    // the first call to scrypt() here might block for up to 30 minutes, when scrypt
    // blocks when reading for /dev/random, which waits for "enough entropy" (but urandom is fine,
    // see the Dockerfile).
    // 2) Upgraded parameters from n=65536 to n=131072 (2^17) per OWASP 2024 recommendations
    // 3) This provides 128MB memory usage vs previous 64MB for better resistance to attacks
    val hash = SCryptUtil.scrypt(plainTextPassword, CurrentScryptN, CurrentScryptR, CurrentScryptP)
    s"$ScryptV2Prefix$hash"
  }

  /**
   * Create legacy-format hash for testing/compatibility purposes.
   * Uses the older n=65536 parameters but still secure enough.
   */
  def saltAndHashPasswordLegacy(plainTextPassword: String): String = {
    val hash = SCryptUtil.scrypt(plainTextPassword, MinimumScryptN, CurrentScryptR, CurrentScryptP)
    s"$ScryptPrefix$hash"
  }

  /**
   * Check if a hash uses weaker parameters and should be upgraded.
   */
  def hashNeedsUpgrade(hash: String): Boolean = {
    hash.startsWith(ScryptPrefix) || hash.startsWith(CleartextPrefix)
  }

  /**
   * Constant-time string comparison to prevent timing attacks.
   * Uses the same algorithm as MessageDigest.isEqual but for strings.
   */
  private def constantTimeStringEquals(a: String, b: String): Boolean = {
    if (a.length != b.length) return false
    
    var result = 0
    for (i <- a.indices) {
      result |= a.charAt(i) ^ b.charAt(i)
    }
    result == 0
  }

}


// vim: fdm=marker et ts=2 sw=2 fo=tcqwn list
