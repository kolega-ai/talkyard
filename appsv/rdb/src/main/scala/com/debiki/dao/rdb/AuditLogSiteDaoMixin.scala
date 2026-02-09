/**
 * Copyright (C) 2015 Kaj Magnus Lindberg
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

package com.debiki.dao.rdb

import scala.collection.Seq
import collection.immutable
import com.debiki.core._
import com.debiki.core.DbDao._
import com.debiki.core.Prelude._
import java.{sql => js, util => ju}
import scala.collection.mutable
import Rdb._
import RdbUtil._
import scala.collection.mutable.ArrayBuffer


/** Inserts, updates, loads audit log entries.
  * 
  * SECURITY NOTE (CWE-532 Fix): This class now implements sensitive data protection
  * for audit logs to prevent exposure of PII including email addresses, IP addresses,
  * browser fingerprints, and cookies. Sensitive data is anonymized before storage
  * using configurable hashing/partial anonymization strategies.
  */
trait AuditLogSiteDaoMixin extends SiteTransaction {
  self: RdbSiteTransaction =>

  var batchId: Option[AuditLogEntryId] = None
  var batchOffset = 0

  def startAuditLogBatch(): Unit = {
    batchId = None
    val (id, _) = nextAuditLogEntryId()
    batchId = Some(id)
    batchOffset = 0
  }


  def nextAuditLogEntryId(): (AuditLogEntryId, Option[AuditLogEntryId]) = {
    batchId foreach { id =>
      val result = (id + batchOffset, batchId)
      batchOffset += 1
      return result
    }
    val query = "select max(audit_id) max_id from audit_log3 where site_id = ?"
    runQuery(query, List(siteId.asAnyRef), rs => {
      rs.next()
      val maxId = rs.getInt("max_id") // null becomes 0, fine
      (maxId + 1, None)
    })
  }


  // ==================== SENSITIVE DATA PROTECTION (CWE-532 Fix) ====================

  /**
   * Anonymizes sensitive personal data in audit log entries to address CWE-532.
   * 
   * This function implements privacy-by-design by anonymizing sensitive fields
   * BEFORE storage, rather than relying solely on post-hoc cleanup. It uses
   * salted hashing with configurable anonymization levels.
   * 
   * @param entry The audit log entry with potentially sensitive data
   * @return A new entry with sensitive fields anonymized according to security policy
   */
  private def anonymizeSensitiveData(entry: AuditLogEntry): AuditLogEntry = {
    // Short-circuit if entry has no sensitive browser/email data
    if (entry.emailAddress.isEmpty && 
        entry.browserIdData.ip == "127.0.0.1" && // System/forgotten IPs are safe
        entry.browserIdData.idCookie.isEmpty && 
        entry.browserIdData.fingerprint == 0) {
      return entry
    }

    val protectedBrowserIdData = anonymizeBrowserIdData(entry.browserIdData)
    val protectedEmail = anonymizeEmailAddress(entry.emailAddress)

    entry.copy(
      emailAddress = protectedEmail,
      browserIdData = protectedBrowserIdData
    )
  }

  /**
   * Anonymizes email addresses using partial anonymization strategy.
   * 
   * Strategy: Hash the local part while preserving domain for security analysis.
   * Example: "user@gmail.com" → "H:a8Bk2mN@gmail.com"
   * 
   * This allows security teams to identify suspicious domains/patterns while
   * protecting individual user identity.
   */
  private def anonymizeEmailAddress(emailOpt: Option[String]): Option[String] = {
    emailOpt.flatMap { email =>
      if (email.trim.isEmpty) return None
      
      val trimmed = email.trim.toLowerCase
      
      // Check if already anonymized (idempotent)
      if (trimmed.startsWith("H:") || trimmed == "[ANONYMIZED]") {
        return Some(trimmed)
      }
      
      // Split email and hash local part, preserve domain
      trimmed.split("@", 2) match {
        case Array(localPart, domain) if domain.nonEmpty =>
          val hashedLocal = saltAndHashForAuditLog(localPart, "email_local", 16)
          Some(s"H:$hashedLocal@$domain")
        case _ =>
          // Invalid email format - hash entirely  
          val fullHash = saltAndHashForAuditLog(trimmed, "email_full", 20)
          Some(s"H:$fullHash")
      }
    }
  }

  /**
   * Anonymizes browser identification data including IP addresses, cookies, and fingerprints.
   * 
   * IP Strategy: Preserve network prefix for geolocation/fraud detection, hash device suffix.
   * Example: "192.168.1.100" → BrowserIdData("192.168.0.0", hashedCookie, hashedFingerprint)
   * 
   * This balances privacy protection with operational security needs.
   */
  private def anonymizeBrowserIdData(browserData: BrowserIdData): BrowserIdData = {
    // Don't anonymize system/forgotten/test IPs
    if (browserData == BrowserIdData.System || 
        browserData == BrowserIdData.Forgotten ||
        browserData == BrowserIdData.Missing ||
        browserData == BrowserIdData.Sysbot) {
      return browserData
    }

    val protectedIp = anonymizeIpAddress(browserData.ip)
    val protectedCookie = anonymizeBrowserCookie(browserData.idCookie)  
    val protectedFingerprint = anonymizeBrowserFingerprint(browserData.fingerprint)

    BrowserIdData(protectedIp, protectedCookie, protectedFingerprint)
  }

  /**
   * Anonymizes IP addresses using subnet-level anonymization.
   * Preserves geographic/network information while protecting device identity.
   */
  private def anonymizeIpAddress(ip: String): String = {
    // Check if already anonymized
    if (ip.contains(".0.0") || ip.startsWith("H:") || ip.startsWith("127.0.0.")) {
      return ip
    }

    if (ip.contains(":")) {
      // IPv6 - preserve first 32 bits, anonymize rest
      val groups = ip.split(":", -1)
      if (groups.length >= 2) {
        s"${groups(0)}:${groups(1)}:0:0:0:0:0:0"
      } else {
        ip // Invalid format, leave as-is
      }
    } else if (ip.contains(".")) {
      // IPv4 - preserve /16 network, zero out /24 and host
      val octets = ip.split("\\.", -1)
      if (octets.length == 4) {
        s"${octets(0)}.${octets(1)}.0.0"
      } else {
        ip // Invalid format, leave as-is  
      }
    } else {
      ip // Unknown format, leave as-is
    }
  }

  /**
   * Anonymizes browser ID cookies using salted hashing.
   * Maintains correlation capability for session analysis while protecting user privacy.
   */
  private def anonymizeBrowserCookie(cookieOpt: Option[String]): Option[String] = {
    cookieOpt.map { cookie =>
      if (cookie.startsWith("H:") || cookie.isEmpty) {
        cookie // Already hashed or empty
      } else {
        val hashedCookie = saltAndHashForAuditLog(cookie, "browser_cookie", 16)
        s"H:$hashedCookie"
      }
    }
  }

  /**
   * Anonymizes browser fingerprints using salted hashing.
   * Fingerprints are highly unique, so full hashing is the only viable anonymization.
   */
  private def anonymizeBrowserFingerprint(fingerprint: Int): Int = {
    if (fingerprint == 0) {
      fingerprint // NoFingerprint constant, leave as-is
    } else {
      // Hash the fingerprint and convert to int
      val hashedStr = saltAndHashForAuditLog(fingerprint.toString, "browser_fingerprint", 8)
      // Take first 8 chars and convert to positive int for storage
      math.abs(hashedStr.hashCode)
    }
  }

  /**
   * Performs salted hashing for audit log anonymization.
   * 
   * Uses a fixed audit-specific salt with field-specific components to prevent 
   * rainbow table attacks while maintaining deterministic correlation.
   * 
   * @param data The data to hash
   * @param fieldType Field-specific salt component (e.g., "email_local", "browser_cookie")
   * @param length Length of hash to return (for storage efficiency)
   * @return Base64-encoded hash truncated to specified length
   */
  private def saltAndHashForAuditLog(data: String, fieldType: String, length: Int): String = {
    // Use a fixed salt specific to audit log anonymization
    // This ensures consistent hashing across application restarts
    val auditSalt = "talkyard_audit_salt_2025_cwe532_fix" 
    val fieldSpecificSalt = s"${auditSalt}_${fieldType}_${data.length}"
    val saltedData = s"$fieldSpecificSalt:$data"
    val hash = hashSha1Base64UrlSafe(saltedData)
    hash.take(length)
  }


  def insertAuditLogEntry(entryNoId: AuditLogEntry): Unit = {
    val entry =
      if (entryNoId.id != AuditLogEntry.UnassignedId) entryNoId
      else {
        val (id, batchId) = nextAuditLogEntryId()
        entryNoId.copy(id = id, batchId = batchId)
      }

    require(entry.id >= 1, "DwE0GMF3")
    require(!entry.batchId.exists(_ > entry.id), "EsE4GGX2")
    require(entry.siteId == siteId, "DwE1FWU6")

    // SECURITY FIX (CWE-532): Apply sensitive data anonymization before storage
    val protectedEntry = anonymizeSensitiveData(entry)
    val statement = s"""
      insert into audit_log3(
        site_id,
        audit_id,
        batch_id,
        doer_id_c,
        doer_true_id_c,
        done_at,
        did_what,
        details,
        email_address,
        ip,
        browser_id_cookie,
        browser_fingerprint,
        anonymity_network,
        country,
        region,
        city,
        page_id,
        page_role,
        post_id,
        post_nr,
        post_action_type,
        post_action_sub_id,
        upload_hash_path,
        upload_file_name,
        size_bytes,
        target_page_id,
        target_post_id,
        target_post_nr,
        target_pat_id_c,
        target_pat_true_id_c,
        target_site_id)
      values (
        ?, ?, ?, ?, ?, ? at time zone 'UTC',
        ?, ?, ?, ?::inet,
        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """

    val values = List[AnyRef](
      protectedEntry.siteId.asAnyRef,
      protectedEntry.id.asAnyRef,
      protectedEntry.batchId.orNullInt,
      protectedEntry.doerTrueId.curId.asAnyRef,
      protectedEntry.doerTrueId.anyTrueId.orNullInt,
      protectedEntry.doneAt.asTimestamp,
      protectedEntry.didWhat.toInt.asAnyRef,
      NullVarchar,
      protectedEntry.emailAddress.orNullVarchar,  // Now anonymized
      protectedEntry.browserIdData.ip,           // Now anonymized
      protectedEntry.browserIdData.idCookie.orNullVarchar,  // Now anonymized
      protectedEntry.browserIdData.fingerprint.asAnyRef,    // Now anonymized
      NullVarchar,
      NullVarchar,
      NullVarchar,
      NullVarchar,
      protectedEntry.pageId.orNullVarchar,
      protectedEntry.pageType.map(_.toInt).orNullInt,
      protectedEntry.uniquePostId.orNullInt,
      protectedEntry.postNr.orNullInt,
      NullInt,
      NullInt,
      protectedEntry.uploadHashPathSuffix.orNullVarchar,
      protectedEntry.uploadFileName.orNullVarchar,
      protectedEntry.sizeBytes.orNullInt,
      protectedEntry.targetPageId.orNullVarchar,
      protectedEntry.targetUniquePostId.orNullInt,
      protectedEntry.targetPostNr.orNullInt,
      protectedEntry.targetPatTrueId.map(_.curId).orNullInt,
      protectedEntry.targetPatTrueId.flatMap(_.anyTrueId).orNullInt,
      protectedEntry.targetSiteId.orNullInt)

    runUpdateSingleRow(statement, values)
  }


  def loadEventsFromAuditLog(limit: i32, newerOrAt: Opt[When] = None,
        newerThanEventId: Opt[EventId] = None, olderOrAt: Opt[When] = None,
        newestFirst: Bo): immutable.Seq[AuditLogEntry] = {
    loadAuditLogEntries(userId = None, Event.RelevantAuditLogEntryTypes,
          newerOrAt = newerOrAt, newerThanEventId = newerThanEventId,
          olderOrAt = olderOrAt, newestFirst = newestFirst,
          limit = limit, inclForgotten = true)
  }


  def loadAuditLogEntries(userId: Opt[PatId], types: ImmSeq[AuditLogEntryType],
        newerOrAt: Opt[When], newerThanEventId: Opt[EventId],
        olderOrAt: Opt[When], newestFirst: Bo, limit: i32,
        inclForgotten: Bo): immutable.Seq[AuditLogEntry] = {

    var sortCol = "audit_id"
    val descOrAsc = if (newestFirst) "desc" else "asc"

    val values = ArrayBuffer(siteId.asAnyRef)

    val andDoerIdIs = userId match {
      case None => ""
      case Some(uId) =>
        values.append(uId.asAnyRef)
        "and doer_id_c = ?"
        // Later, but only sometimes:  or doer_true_id_c = ?)   [sql_true_id_eq]
    }

    val andDidWhatEqType = if (types.isEmpty) "" else {
      values.appendAll(types.map(_.toInt.asAnyRef))
      s"and did_what in (${makeInListFor(types)})"
    }

    // Non-anonymized entries have forgotten = 0 (instead of 1, 2)
    val andSkipForgotten = inclForgotten ? "" | "and forgotten = 0"

    val andNewerOrAt = newerOrAt match {
      case None => ""
      case Some(when) =>
        sortCol = "done_at"
        values.append(when.asTimestamp)
        "and done_at >= ?"
    }

    val andOlderOrAt = olderOrAt match {
      case None => ""
      case Some(when) =>
        sortCol = "done_at"
        values.append(when.asTimestamp)
        "and done_at <= ?"
    }

    val andIdAbove = newerThanEventId match {
      case None => ""
      case Some(id) =>
        // For now, prioritize sorting by id, if both dates and ids specified.
        sortCol = "audit_id"
        values.append(id.asAnyRef)
        "and audit_id > ?"
    }

    val query = s"""
      select * from audit_log3
      where site_id = ?
          $andDoerIdIs
          $andDidWhatEqType
          $andSkipForgotten
          $andNewerOrAt
          $andOlderOrAt
          $andIdAbove
      order by $sortCol $descOrAsc
      limit $limit  """

    runQueryFindMany(query, values.toList, rs => {
      getAuditLogEntry(rs)
    })
  }


  def loadCreatePostAuditLogEntry(postId: PostId): Option[AuditLogEntry] = {
    val query = s"""
      select * from audit_log3
      where site_id = ? and post_id = ?
      and did_what in (${AuditLogEntryType.NewPage.toInt}, ${AuditLogEntryType.NewReply.toInt})
      order by done_at limit 1
      """
    runQueryFindOneOrNone(query, List(siteId.asAnyRef, postId.asAnyRef), rs => {
      getAuditLogEntry(rs)
    })
  }


  def loadCreatePostAuditLogEntriesBy(browserIdData: BrowserIdData, limit: Int, orderBy: OrderBy)
        : Seq[AuditLogEntry] = {
    dieIf(orderBy != OrderBy.MostRecentFirst, "EdE5PKB20", "Unimpl")

    val values = ArrayBuffer(siteId.asAnyRef, browserIdData.ip)

    val ipQuery = s"""(
      select * from audit_log3
      where site_id = ? and ip = ?::inet
      and did_what in (${AuditLogEntryType.NewPage.toInt}, ${AuditLogEntryType.NewReply.toInt})
      order by done_at desc limit $limit)"""

    val cookieQuery = browserIdData.idCookie match {
      case None => ""
      case Some(idCookie) =>
        values.append(siteId.asAnyRef)
        values.append(idCookie)
        s"""
          union (
            select * from audit_log3
            where site_id = ? and browser_id_cookie = ?
            and did_what in (${AuditLogEntryType.NewPage.toInt}, ${AuditLogEntryType.NewReply.toInt})
            order by done_at desc limit $limit)"""
    }

    val query = ipQuery + cookieQuery
    val entries = runQueryFindMany(query, values.toList, rs => {
      getAuditLogEntry(rs)
    })
    entries.sortBy(-_.doneAt.getTime)
  }


  private def getAuditLogEntry(rs: js.ResultSet): AuditLogEntry = {
    val didWhatNr = rs.getInt("did_what")
    val didWhat = AuditLogEntryType.fromInt(didWhatNr
                    ) getOrElse AuditLogEntryType.Unknown(didWhatNr)
    AuditLogEntry(
      siteId = siteId,
      id = rs.getInt("audit_id"),
      batchId = getOptInt(rs, "audit_id"),
      didWhat = didWhat,
      doerTrueId = TrueId(getInt32(rs, "doer_id_c"),
                      anyTrueId = getOptInt(rs, "doer_true_id_c")),
      doneAt = getDate(rs, "done_at"),
      emailAddress = Option(rs.getString("email_address")),
      browserIdData = getBrowserIdData(rs),
      browserLocation = None,
      pageId = Option(rs.getString("page_id")),
      pageType = getOptInt(rs, "page_role").flatMap(PageType.fromInt),
      uniquePostId = getOptInt(rs, "post_id"),
      postNr = getOptInt(rs, "post_nr"),
      uploadHashPathSuffix = Option(rs.getString("upload_hash_path")),
      uploadFileName = Option(rs.getString("upload_file_name")),
      sizeBytes = getOptInt(rs, "size_bytes"),
      targetUniquePostId = getOptInt(rs, "target_post_id"),
      targetPageId = Option(rs.getString("target_page_id")),
      targetPostNr = getOptInt(rs, "target_post_nr"),
      targetPatTrueId = getOptInt(rs, "target_pat_id_c").map(id =>
            TrueId(id, anyTrueId = getOptInt(rs, "target_pat_true_id_c"))),
      targetSiteId = getOptInt(rs, "target_site_id"),
      isLoading = true)
  }

  private def getBrowserIdData(rs: js.ResultSet) = {
    val idData = BrowserIdData(
      ip = rs.getString("ip"),
      idCookie = Option(rs.getString("browser_id_cookie")),
      fingerprint = rs.getInt("browser_fingerprint"))
    // Nod needed but saves a bit memory:
    if (idData == BrowserIdData.System) BrowserIdData.System
    else if (idData == BrowserIdData.Forgotten) BrowserIdData.Forgotten
    else idData
  }

}
